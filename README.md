# MOPS — сервис конечного автомата

Промежуточный этап задания 3.1.1: один работающий автомат, FIFO-очередь сообщений,
динамические обработчики, состояния и переходы, YAML-конфигурация и REST API.

## Запуск

Нужны JDK 27 и Docker Compose. Gradle Wrapper включен в репозиторий.

```bash
docker compose up -d --wait
./gradlew bootRun
```

API: `http://localhost:8080/api/automaton`.
PostgreSQL: `localhost:5432`, база/пользователь/пароль по умолчанию — `mops`.
Настройки приложения переопределяются через `DB_URL`, `DB_USER`, `DB_PASSWORD`.
Compose также читает `DB_PASSWORD`. Данные БД сохраняются в Docker volume.
Остановка БД: `docker compose down`.

## Тесты

```bash
./gradlew test
```

Тесты запускаются без Docker: ядро проверяется с хранилищем в памяти, API и JDBC —
с H2. Отчет: `build/reports/tests/test/index.html`.
Проверяются жизненный цикл с возвратом, FIFO, обработка без перехода, динамические
изменения, ошибки обработчика и хранилища, восстановление состояния, YAML и REST.

## Устройство

- `fsm/Automaton` — независимый от Spring класс: очередь, обработчики, текущее
  состояние, изменение графа. Один фоновый поток последовательно обрабатывает сообщения.
- `fsm/Graph` — состояния и переходы как данные, без привязки к предметной области.
- `config/YamlConfiguration` — чтение/запись YAML.
- `persistence/JdbcSnapshotStore` — сохранение снимка в PostgreSQL.
- `api/AutomatonController` — HTTP-интерфейс сервиса.

В таблице `automaton` одна строка: `id = 1`, `configuration TEXT` — YAML-снимок
графа и текущего состояния. Для одного небольшого автомата это позволяет сохранять
их вместе одной транзакцией без отдельных таблиц для каждого элемента графа.
В памяти новый снимок публикуется только после успешного сохранения в БД.

При первом запуске читается `src/main/resources/automaton.yaml`. При следующих
запусках восстанавливается снимок из БД. Изменения графа и выполнение обработчика
синхронизированы; новые сообщения могут поступать в очередь во время обработки.
Приложение рассчитано на один запущенный экземпляр.

## Правила работы

- Сообщение содержит `event` и необязательный объект `payload`.
- Обработчик выбирается по событию. Неизвестное событие отклоняется.
- После успешного обработчика применяется переход из текущего состояния по событию.
  Если перехода нет, обработчик выполняется без изменения состояния.
- Ошибка обработчика или сохранения не меняет снимок автомата. Внешние побочные
  эффекты обработчика автоматически не откатываются. Ошибочное сообщение удаляется
  из очереди, ошибка возвращается отправителю; следующие сообщения обрабатываются.
- Для пары `(from, event)` разрешен один переход; обе вершины должны существовать.
- При удалении состояния удаляются связанные переходы. Текущее и начальное
  состояния удалять нельзя.
- Замена YAML-конфигурации сохраняет текущее состояние: оно должно присутствовать
  в новом графе. `initialState` определяет состояние при первом создании автомата.
- Очередь хранится в памяти: ожидающие сообщения теряются при перезапуске.
  Сообщения обрабатываются по графу, действующему в момент обработки.
- HTTP-запрос отправки сообщения асинхронно ожидает результата обработки и возвращает
  снимок после нее. Отмена HTTP-запроса не отменяет сообщение.

## REST API

| Метод | Путь относительно `/api/automaton` | Назначение |
|---|---|---|
| GET | пустой путь | Граф и текущее состояние |
| PUT | `/configuration` | Заменить граф из YAML |
| POST | `/states` | Добавить состояние: `{"id":"archived"}` |
| DELETE | `/states/{id}` | Удалить состояние и связанные переходы |
| POST | `/transitions` | Добавить переход: `{"from":"review","event":"approve","to":"archived"}` |
| DELETE | `/transitions?from=review&event=approve` | Удалить переход |
| POST | `/messages` | Отправить сообщение: `{"event":"submit","payload":{}}` |

Ответы — JSON; ошибки входных данных — HTTP 400 с описанием причины.

### Пример

Начальный граф: `draft → review → approved`, возврат `review → draft` по `reject`.
Обработчики `submit`, `approve`, `reject` в примере записывают событие и данные в лог.

```bash
curl http://localhost:8080/api/automaton

curl -X POST http://localhost:8080/api/automaton/messages \
  -H 'Content-Type: application/json' \
  -d '{"event":"submit","payload":{"documentId":123}}'

curl -X POST http://localhost:8080/api/automaton/messages \
  -H 'Content-Type: application/json' -d '{"event":"reject"}'

curl -X POST http://localhost:8080/api/automaton/states \
  -H 'Content-Type: application/json' -d '{"id":"archived"}'

curl -X POST http://localhost:8080/api/automaton/transitions \
  -H 'Content-Type: application/json' \
  -d '{"from":"approved","event":"approve","to":"archived"}'
```

Загрузка YAML (текущее состояние должно входить в загружаемый граф):

```bash
curl -X PUT http://localhost:8080/api/automaton/configuration \
  -H 'Content-Type: application/yaml' \
  --data-binary @src/main/resources/automaton.yaml
```

### Новые обработчики

Логика регистрируется Java-функцией на работающем экземпляре; повторная регистрация
заменяет обработчик. Состояния и события — строки, перекомпиляция ядра не нужна.

```java
automaton.registerHandler("archive", message -> {
    System.out.println("Archive: " + message.payload());
});
automaton.addState("archived");
automaton.addTransition(new Transition("approved", "archive", "archived"));
automaton.submit(new Message("archive", Map.of("documentId", 123)));
```

При запуске стандартные обработчики подключаются в `AutomatonConfiguration`.
REST/YAML меняют граф, а исполняемые Java-функции регистрируются через API класса.
Обработчики не должны ждать завершения других сообщений той же очереди: ее
обслуживает один поток.
