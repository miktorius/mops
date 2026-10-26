const api = '/api/automaton';
const graphElement = document.querySelector('#graph');
const graphError = document.querySelector('#graph-error');
const currentState = document.querySelector('#current-state');
const eventInput = document.querySelector('#event');
const refreshButton = document.querySelector('#refresh');
const result = document.querySelector('#result');
const themeButton = document.querySelector('#theme-toggle');
let snapshot;
let renderId = 0;

// A pinned version keeps diagram rendering consistent between page loads.
const renderer = import('https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.esm.min.mjs')
  .then(({ default: mermaid }) => mermaid)
  .catch(() => null);

function isDarkTheme() {
  return document.documentElement.dataset.theme !== 'light';
}

function configureRenderer(mermaid) {
  const dark = isDarkTheme();
  mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'base',
      themeVariables: {
        darkMode: dark,
        background: dark ? '#1e293b' : '#ffffff',
        primaryColor: dark ? '#334155' : '#eef2ff',
        primaryTextColor: dark ? '#e2e8f0' : '#18283b',
        primaryBorderColor: dark ? '#94a3b8' : '#64748b',
        lineColor: dark ? '#94a3b8' : '#536579',
        edgeLabelBackground: dark ? '#1e293b' : '#ffffff',
        tertiaryColor: dark ? '#0f172a' : '#f3f6fa'
      },
      flowchart: { htmlLabels: false }
    });
}

function updateThemeButton() {
  themeButton.textContent = isDarkTheme() ? 'Светлая тема' : 'Тёмная тема';
  themeButton.setAttribute('aria-pressed', String(isDarkTheme()));
}

updateThemeButton();
themeButton.addEventListener('click', async () => {
  document.documentElement.dataset.theme = isDarkTheme() ? 'light' : 'dark';
  try {
    localStorage.setItem('theme', document.documentElement.dataset.theme);
  } catch {
    // Theme switching still works if browser storage is unavailable.
  }
  updateThemeButton();
  setBusy(true);
  try {
    if (snapshot) await showSnapshot(snapshot);
  } finally {
    setBusy(false);
  }
});

async function request(path = '', options = {}) {
  const response = await fetch(api + path, options);
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.detail || body.message || `Ошибка HTTP ${response.status}`);
  }
  return body;
}

function showResult(message, error = false) {
  result.textContent = message;
  result.className = error ? 'error' : 'success';
}

function setBusy(busy) {
  refreshButton.disabled = busy;
  themeButton.disabled = busy;
  document.querySelectorAll('fieldset').forEach(fieldset => {
    fieldset.disabled = busy || !snapshot;
  });
}

// Escape only Mermaid/HTML delimiters. Encoding every letter breaks Mermaid's
// label width calculation and makes short event names wrap into several lines.
function label(text) {
  return text.replace(/[&"<>#\r\n]/g, character => {
    if (character === '\r' || character === '\n') return ' ';
    return `#${character.codePointAt(0)};`;
  });
}

async function showSnapshot(updated) {
  snapshot = updated;
  currentState.textContent = updated.currentState;
  showEditor(updated);
  const events = [...new Set(updated.graph.transitions
    .filter(transition => transition.from === updated.currentState)
    .map(transition => transition.event))];
  document.querySelector('#events').replaceChildren(...updated.graph.events.map(event => {
    const option = document.createElement('option');
    option.value = event;
    return option;
  }));
  document.querySelector('#suggestions').textContent = events.length
    ? `Переходы из текущего состояния: ${events.join(', ')}`
    : 'Из текущего состояния нет переходов.';

  document.querySelector('#transitions').replaceChildren(...updated.graph.transitions.map(transition => {
    const item = document.createElement('li');
    item.textContent = `${transition.from} → ${transition.to} — ${transition.event}`;
    return item;
  }));

  graphElement.replaceChildren();
  graphError.hidden = true;
  try {
    const mermaid = await renderer;
    if (!mermaid) throw new Error('Не удалось загрузить Mermaid. Проверьте интернет и перезагрузите страницу. Переходы доступны списком.');
    configureRenderer(mermaid);

    const ids = new Map(updated.graph.states.map((state, index) => [state, `s${index}`]));
    const lines = ['flowchart LR'];
    for (const state of updated.graph.states) {
      const title = state + (state === updated.graph.initialState ? ' (начальное)' : '')
        + (updated.graph.finalStates.includes(state) ? ' (конечное)' : '');
      lines.push(updated.graph.finalStates.includes(state)
        ? `${ids.get(state)}(["${label(title)}"])`
        : `${ids.get(state)}["${label(title)}"]`);
    }
    for (const transition of updated.graph.transitions) {
      lines.push(`${ids.get(transition.from)} -->|"${label(transition.event)}"| ${ids.get(transition.to)}`);
    }
    lines.push(isDarkTheme()
      ? 'classDef current fill:#064e3b,stroke:#6ee7b7,stroke-width:3px,color:#d1fae5'
      : 'classDef current fill:#d1fae5,stroke:#08763f,stroke-width:3px,color:#064e3b');
    lines.push(`class ${ids.get(updated.currentState)} current`);
    const { svg } = await mermaid.render(`automaton-${++renderId}`, lines.join('\n'));
    graphElement.innerHTML = svg;
  } catch (error) {
    graphError.textContent = error.message;
    graphError.hidden = false;
  }
}

async function refresh() {
  setBusy(true);
  try {
    await showSnapshot(await request());
    showResult('Граф обновлён.');
  } catch (error) {
    showResult(`Не удалось загрузить граф: ${error.message}`, true);
  } finally {
    setBusy(false);
  }
}

refreshButton.addEventListener('click', refresh);
document.querySelector('#message-form').addEventListener('submit', event => {
  event.preventDefault();
  sendEvent(eventInput.value.trim());
});

async function sendEvent(name) {
  setBusy(true);
  try {
    if (!name) throw new Error('Введите событие.');
    const payload = JSON.parse(document.querySelector('#payload').value);
    if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
      throw new Error('Данные сообщения должны быть JSON-объектом, например {}.');
    }
    const previousState = snapshot.currentState;
    const updated = await request('/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event: name, payload })
    });
    await showSnapshot(updated);
    showResult(`Событие «${name}» обработано: ${previousState} → ${updated.currentState}.`);
  } catch (error) {
    showResult(error.message, true);
  } finally {
    setBusy(false);
  }
}

function actionButton(text, action) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = text;
  button.addEventListener('click', action);
  return button;
}

function editorRow(text, action) {
  const item = document.createElement('li');
  const title = document.createElement('span');
  title.textContent = text;
  item.append(title);
  if (action) item.append(actionButton('Удалить', action));
  return item;
}

function showEditor(updated) {
  const { graph, currentState } = updated;
  const terminal = graph.finalStates.includes(currentState);
  document.querySelector('#registered-events').replaceChildren(...graph.events.map(event =>
    editorRow(event, () => {
      const count = graph.transitions.filter(transition => transition.event === event).length;
      if (window.confirm(`Удалить событие «${event}»? Связанных переходов будет удалено: ${count}.`)) {
        changeGraph(`/events/${encodeURIComponent(event)}`, 'DELETE', null, 'Событие и связанные переходы удалены.');
      }
    })));
  const eventSelect = document.querySelector('#transition-event');
  const previousEvent = eventSelect.value;
  eventSelect.replaceChildren(...graph.events.map(event => new Option(event, event)));
  if (graph.events.includes(previousEvent)) eventSelect.value = previousEvent;
  const initialSelect = document.querySelector('#initial-state');
  initialSelect.replaceChildren(...graph.states.map(state => new Option(state, state)));
  initialSelect.value = graph.initialState;
  for (const id of ['transition-from', 'transition-to']) {
    const select = document.getElementById(id);
    const previous = select.value;
    select.replaceChildren(...graph.states.map(state => new Option(state, state)));
    select.value = graph.states.includes(previous) ? previous : currentState;
  }

  document.querySelector('#state-list').replaceChildren(...graph.states.map(state => {
    const markers = [];
    if (state === graph.initialState) markers.push('начальное');
    if (state === currentState) markers.push('текущее');
    if (graph.finalStates.includes(state)) markers.push('конечное');
    const row = editorRow(markers.length ? `${state} (${markers.join(', ')})` : state,
      state === graph.initialState || state === currentState ? null
        : () => changeGraph(`/states/${encodeURIComponent(state)}`, 'DELETE', null, 'Состояние удалено.'));
    const terminalLabel = document.createElement('label');
    terminalLabel.className = 'terminal-toggle';
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = graph.finalStates.includes(state);
    checkbox.addEventListener('change', () => changeGraph('/final-state', 'PUT',
      { id: state, terminal: checkbox.checked }, 'Признак конечного состояния обновлён.'));
    terminalLabel.append(checkbox, 'Конечное');
    row.append(terminalLabel);
    return row;
  }));

  document.querySelector('#transition-list').replaceChildren(...graph.transitions.map(transition => {
    const query = new URLSearchParams({ from: transition.from, event: transition.event });
    return editorRow(`${transition.from} → ${transition.to} — ${transition.event}`,
      () => changeGraph(`/transitions?${query}`, 'DELETE', null, 'Переход удалён.'));
  }));

  const available = terminal ? [] : graph.transitions.filter(transition => transition.from === currentState);
  const actions = document.querySelector('#available-transitions');
  actions.replaceChildren(...available.map(transition =>
    actionButton(`${transition.event} → ${transition.to}`, () => sendEvent(transition.event))));
  if (!available.length) actions.textContent = terminal
    ? 'Автомат в конечном состоянии. Чтобы продолжить, снимите отметку «Конечное».'
    : 'Нет исходящих переходов. Добавьте переход ниже.';
}

async function changeGraph(path, method, body, message) {
  setBusy(true);
  try {
    const options = { method };
    if (body !== null) {
      options.headers = { 'Content-Type': 'application/json' };
      options.body = JSON.stringify(body);
    }
    await showSnapshot(await request(path, options));
    showResult(message);
  } catch (error) {
    if (snapshot) showEditor(snapshot);
    showResult(error.message, true);
  } finally {
    setBusy(false);
  }
}

document.querySelector('#state-form').addEventListener('submit', event => {
  event.preventDefault();
  const id = document.querySelector('#state-id').value.trim();
  if (!id) return showResult('Введите имя состояния.', true);
  changeGraph('/states', 'POST', { id }, 'Состояние добавлено.');
});

document.querySelector('#event-form').addEventListener('submit', event => {
  event.preventDefault();
  const id = document.querySelector('#event-id').value.trim();
  if (!id) return showResult('Введите имя события.', true);
  changeGraph('/events', 'POST', { id }, 'Событие добавлено.');
});

document.querySelector('#initial-state-form').addEventListener('submit', event => {
  event.preventDefault();
  changeGraph('/initial-state', 'PUT', { id: document.querySelector('#initial-state').value },
    'Начальное состояние изменено. Текущее состояние сохранено.');
});

document.querySelector('#transition-form').addEventListener('submit', event => {
  event.preventDefault();
  const transition = {
    from: document.querySelector('#transition-from').value,
    event: document.querySelector('#transition-event').value.trim(),
    to: document.querySelector('#transition-to').value
  };
  if (!transition.event) return showResult('Введите имя события.', true);
  changeGraph('/transitions', 'POST', transition, 'Переход добавлен.');
});

refresh();
