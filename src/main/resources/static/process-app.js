import {renderProcess} from './process-diagram.js';

const element = id => document.getElementById(id);
const elements = Object.fromEntries([
    'user', 'object', 'action', 'start', 'execute', 'refresh', 'notice', 'roles',
    'process-title', 'current-state', 'diagram', 'action-hint', 'result',
    'object-title', 'object-values', 'transitions', 'result-json',
].map(id => [id, element(id)]));

let model;
let objects = [];
let instances = [];
let lastResult;
let busy = false;

function fillOptions(select, items, valueFor, labelFor) {
    select.replaceChildren(...items.map(item => {
        const option = document.createElement('option');
        option.value = valueFor(item);
        option.textContent = labelFor(item);
        return option;
    }));
}

function selectedObject() {
    return objects.find(object => object.id === elements.object.value);
}

function selectedTemplate() {
    return (model.processes || []).find(template => template.class === selectedObject()?.class);
}

function roleNames(roles = []) {
    return (roles || []).map(id => (model.roles || []).find(role => role.id === id)?.title || id).join(', ');
}

function stateTitle(stateId) {
    return selectedTemplate()?.states?.find(state => state.id === stateId)?.title || stateId;
}

function actionTitle(actionId) {
    return selectedTemplate()?.actions?.find(action => action.id === actionId)?.title || actionId;
}

function classParameters(classId) {
    const definition = (model.classes || []).find(item => item.id === classId);
    if (!definition) return new Map();
    const parameters = definition.parent ? classParameters(definition.parent) : new Map();
    for (const parameter of definition.parameters || []) {
        parameters.set(parameter.id, parameter);
    }
    return parameters;
}

function formatValue(value) {
    if (value === null || value === undefined) return 'Не задано';
    if (Array.isArray(value)) {
        return value.length ? value.map(id => objects.find(object => object.id === id)?.values?.title || id).join(', ') : 'Нет элементов';
    }
    return typeof value === 'number' ? value.toLocaleString('ru-RU') : String(value);
}

function describePredicate(predicate) {
    if (!predicate) return 'Неизвестное условие';
    if (predicate.class) {
        const title = (model.classes || []).find(item => item.id === predicate.class)?.title || predicate.class;
        return `Объект относится к классу «${title}»`;
    }

    const path = predicate.parameter.split('.');
    const parameter = classParameters(selectedObject()?.class).get(path[0]);
    let title = parameter?.title || path[0];
    if (path.length > 1) {
        const memberParameter = classParameters(parameter?.type).get(path[1]);
        const aggregate = predicate.aggregate === 'all' ? 'У каждого элемента' : 'Хотя бы у одного элемента';
        title = `${aggregate} «${title}»: ${memberParameter?.title || path[1]}`;
    }

    if (predicate.operator === 'filled') return `${title} — заполнено`;
    if (predicate.operator === 'in_range') {
        if (path.length > 1) return `${title} — в диапазоне своего класса`;
        const composition = !['integer', 'decimal', 'string'].includes(parameter?.type);
        const lower = composition ? parameter?.quantity_min : parameter?.min;
        const upper = composition ? parameter?.quantity_max : parameter?.max;
        const bounds = [];
        if (lower != null) bounds.push(`от ${formatValue(lower)}`);
        if (upper != null) bounds.push(`до ${formatValue(upper)}`);
        return `${composition ? 'Количество: ' : ''}${title} — ${bounds.join(' ') || 'в диапазоне класса'}`;
    }
    return `${title} ${predicate.operator} ${formatValue(predicate.value)}`;
}

function describeCondition(condition) {
    if (condition == null) return 'Без дополнительных условий';
    if (!condition.length) return 'Условие всегда ложно';
    const predicates = selectedTemplate()?.predicates || [];
    return condition.map(group => {
        if (!group.length) return 'Без дополнительных условий';
        return group.map(term => {
            const negated = term.startsWith('not ');
            const id = negated ? term.slice(4).trim() : term;
            const description = describePredicate(predicates.find(predicate => predicate.id === id));
            return negated ? `НЕ (${description})` : description;
        }).join('\nИ ');
    }).join('\nИЛИ\n');
}

function renderObject() {
    const object = selectedObject();
    elements['object-title'].textContent = object?.values?.title || object?.id || 'Параметры';
    const rows = [];
    for (const parameter of classParameters(object?.class).values()) {
        const row = document.createElement('div');
        const name = document.createElement('dt');
        const value = document.createElement('dd');
        name.textContent = parameter.title || parameter.id;
        value.textContent = formatValue(object.values[parameter.id]);
        row.append(name, value);
        rows.push(row);
    }
    elements['object-values'].replaceChildren(...rows);
}

function renderTransitions(template, state) {
    const transitions = state && !state.final
        ? (template.transitions || []).filter(transition => transition.from === state.id) : [];
    const cards = transitions.map(transition => {
        const card = document.createElement('div');
        const heading = document.createElement('strong');
        const condition = document.createElement('p');
        card.className = 'transition-item';
        heading.textContent = `${actionTitle(transition.action)} → ${stateTitle(transition.to)}`;
        condition.textContent = describeCondition(transition.condition);
        card.append(heading, condition);
        return card;
    });
    if (!cards.length) {
        const message = document.createElement('p');
        message.className = 'muted';
        message.textContent = state?.final ? 'Процесс завершён. Дальнейших переходов нет.' : 'Запустите процесс, чтобы увидеть доступные переходы.';
        cards.push(message);
    }
    elements.transitions.replaceChildren(...cards);
    return transitions;
}

function render() {
    const template = selectedTemplate();
    const instance = instances.find(instance => instance.object === elements.object.value);
    const state = template?.states?.find(state => state.id === instance?.state);
    const user = (model.users || []).find(user => user.id === elements.user.value);
    elements.roles.replaceChildren(...(user?.roles || []).map(role => {
        const chip = document.createElement('span');
        chip.className = 'role-chip';
        chip.textContent = roleNames([role]);
        return chip;
    }));

    elements['process-title'].textContent = template?.title || template?.process || 'Диаграмма состояний';
    elements['current-state'].textContent = state ? stateTitle(state.id) : 'Процесс не запущен';
    elements['current-state'].className = `state-badge${state ? '' : ' idle'}`;
    renderProcess(elements.diagram, template, state?.id,
        lastResult?.object === elements.object.value ? lastResult : null,
        {state: stateTitle, action: actionTitle, condition: describeCondition, roles: roleNames});

    const transitions = renderTransitions(template, state);
    const previousAction = elements.action.value;
    const availableActions = new Set(transitions.map(transition => transition.action));
    const actionIds = (template?.actions || []).filter(action => availableActions.has(action.id)).map(action => action.id);
    fillOptions(elements.action, actionIds, id => id, actionTitle);
    if (actionIds.includes(previousAction)) elements.action.value = previousAction;
    elements.start.disabled = busy || !template || !user || !!instance;
    elements.execute.disabled = busy || !user || !actionIds.length;
    elements.refresh.disabled = busy;
    elements.user.disabled = busy;
    elements.object.disabled = busy;
    elements.action.disabled = busy || !actionIds.length;
    elements['action-hint'].textContent = state?.final ? 'Процесс завершён'
        : state ? `Действия выполняет: ${roleNames(state.roles)}`
            : template ? `Запуск разрешён: ${roleNames(template.create_roles)}` : '';
    renderObject();
}

function showCurrentStatus() {
    const instance = instances.find(instance => instance.object === elements.object.value);
    const state = selectedTemplate()?.states?.find(state => state.id === instance?.state);
    elements.result.className = 'result';
    elements.result.textContent = state?.final
        ? `Процесс завершён. Итог: ${stateTitle(state.id)}.`
        : state ? `Процесс запущен. Текущее состояние: ${stateTitle(state.id)}. Выберите действие для продолжения.`
            : 'Запустите процесс над выбранным объектом.';
}

function friendlyReason(reason) {
    if (!reason) return '';
    if (reason.startsWith('Required role: ')) {
        const roles = reason.slice('Required role: '.length).replace(/[\[\]]/g, '').split(',').map(role => role.trim());
        return `Недостаточно прав. Нужна одна из ролей: ${roleNames(roles)}.`;
    }
    return {
        'Transition condition is false': 'Условия перехода не выполнены. Проверьте параметры объекта и правила перехода.',
        'Process already started': 'Процесс над этим объектом уже запущен.',
        'Process not started': 'Сначала запустите процесс.',
        'Process is completed': 'Процесс уже завершён.',
    }[reason] || reason;
}

async function refreshInstances() {
    const response = await fetch('/api/processes');
    if (!response.ok) throw new Error('Не удалось получить состояния процессов.');
    instances = await response.json();
    render();
}

async function sendCommand(type) {
    const command = {type, user: elements.user.value, object: elements.object.value};
    if (type === 'action') command.action = elements.action.value;
    busy = true;
    render();
    try {
        const response = await fetch('/api/processes/commands', {
            method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(command),
        });
        if (!response.ok) throw new Error(`Ошибка отправки команды: ${response.status}`);
        lastResult = await response.json();
        elements['result-json'].textContent = JSON.stringify(lastResult, null, 2);
        elements.result.className = `result ${lastResult.result}`;
        elements.result.textContent = lastResult.result === 'accepted'
            ? `${type === 'start' ? 'Процесс запущен' : 'Действие выполнено'}. Текущее состояние: ${stateTitle(lastResult.state_after)}.`
            : `Команда ${lastResult.result === 'rejected' ? 'отклонена' : 'завершилась ошибкой'}. ${friendlyReason(lastResult.reason)}`;
        await refreshInstances();
    } catch (error) {
        elements.result.className = 'result error';
        elements.result.textContent = error.message;
    } finally {
        busy = false;
        render();
    }
}

async function loadModel() {
    try {
        const [modelResponse, objectsResponse] = await Promise.all([
            fetch('/api/processes/model'), fetch('/api/processes/objects'),
        ]);
        if (!modelResponse.ok || !objectsResponse.ok) throw new Error('Не удалось загрузить модель процессов.');
        model = await modelResponse.json();
        objects = await objectsResponse.json();
        const processObjects = objects.filter(object => (model.processes || []).some(template => template.class === object.class));
        fillOptions(elements.user, model.users || [], user => user.id, user => user.name || user.id);
        fillOptions(elements.object, processObjects, object => object.id, object => object.values.title || object.id);
        elements.notice.textContent = processObjects.length
            ? 'Выберите объект, запустите процесс и наблюдайте за переходами на диаграмме.'
            : 'В модели нет объектов с шаблоном процесса.';
        await refreshInstances();
        showCurrentStatus();
    } catch (error) {
        elements.notice.textContent = error.message;
    }
}

elements.object.addEventListener('change', () => {
    lastResult = null;
    render();
    showCurrentStatus();
});
elements.user.addEventListener('change', render);
elements.start.addEventListener('click', () => sendCommand('start'));
elements.execute.addEventListener('click', () => sendCommand('action'));
elements.refresh.addEventListener('click', () => refreshInstances().catch(error => {
    elements.notice.textContent = error.message;
}));

await loadModel();
