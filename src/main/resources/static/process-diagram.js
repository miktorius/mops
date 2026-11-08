const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
const NODE_WIDTH = 184;
const NODE_HEIGHT = 84;
const COLUMN_GAP = 296;
const ROW_GAP = 156;

function svgElement(tag, attributes = {}, text) {
    const element = document.createElementNS(SVG_NAMESPACE, tag);
    for (const [name, value] of Object.entries(attributes)) {
        element.setAttribute(name, value);
    }
    if (text !== undefined) {
        element.textContent = text;
    }
    return element;
}

// Remove DFS back edges before assigning ranks; cycles are drawn above the graph.
export function layoutProcess(template) {
    const states = template.states || [];
    const transitions = template.transitions || [];
    const visited = new Set();
    const active = new Set();
    const backEdges = new Set();
    const ordered = [];

    function visit(stateId) {
        if (visited.has(stateId)) return;
        visited.add(stateId);
        active.add(stateId);
        for (const transition of transitions.filter(edge => edge.from === stateId)) {
            if (active.has(transition.to)) {
                backEdges.add(transition);
            } else {
                visit(transition.to);
            }
        }
        active.delete(stateId);
        ordered.push(stateId);
    }

    for (const state of [...states.filter(state => state.initial), ...states]) {
        visit(state.id);
    }

    const ranks = new Map(states.map(state => [state.id, 0]));
    for (const stateId of ordered.reverse()) {
        for (const transition of transitions.filter(edge => edge.from === stateId && !backEdges.has(edge))) {
            ranks.set(transition.to, Math.max(ranks.get(transition.to), ranks.get(stateId) + 1));
        }
    }

    const columns = new Map();
    for (const state of states) {
        const rank = ranks.get(state.id);
        if (!columns.has(rank)) columns.set(rank, []);
        columns.get(rank).push(state);
    }

    const rowCount = Math.max(1, ...[...columns.values()].map(column => column.length));
    const top = 44 + backEdges.size * 40;
    const positions = new Map();
    for (const [rank, column] of columns) {
        column.forEach((state, row) => positions.set(state.id, {
            x: 70 + rank * COLUMN_GAP,
            y: top + row * ROW_GAP,
            rank,
        }));
    }

    const bottom = top + (rowCount - 1) * ROW_GAP + NODE_HEIGHT;
    const longEdges = transitions.filter(edge => !backEdges.has(edge)
        && ranks.get(edge.to) - ranks.get(edge.from) > 1);
    return {
        positions, backEdges, longEdges, top, bottom,
        width: 140 + Math.max(0, ...ranks.values()) * COLUMN_GAP + NODE_WIDTH,
        height: bottom + 56 + longEdges.length * 44,
    };
}

// Every connection follows the same orthogonal route with rounded corners.
function roundedPath(points, radius = 12) {
    const distinct = points.filter((point, index) => index === 0
        || point.x !== points[index - 1].x || point.y !== points[index - 1].y);
    const route = distinct.filter((point, index) => {
        if (index === 0 || index === distinct.length - 1) return true;
        const before = distinct[index - 1];
        const after = distinct[index + 1];
        return !((before.x === point.x && point.x === after.x)
            || (before.y === point.y && point.y === after.y));
    });
    let path = `M ${route[0].x} ${route[0].y}`;
    for (let index = 1; index < route.length - 1; index++) {
        const before = route[index - 1];
        const corner = route[index];
        const after = route[index + 1];
        const incoming = Math.hypot(corner.x - before.x, corner.y - before.y);
        const outgoing = Math.hypot(after.x - corner.x, after.y - corner.y);
        const bend = Math.min(radius, incoming / 2, outgoing / 2);
        const entry = {
            x: corner.x + (before.x - corner.x) * bend / incoming,
            y: corner.y + (before.y - corner.y) * bend / incoming,
        };
        const exit = {
            x: corner.x + (after.x - corner.x) * bend / outgoing,
            y: corner.y + (after.y - corner.y) * bend / outgoing,
        };
        path += ` L ${entry.x} ${entry.y} Q ${corner.x} ${corner.y} ${exit.x} ${exit.y}`;
    }
    const end = route[route.length - 1];
    return `${path} L ${end.x} ${end.y}`;
}

function edgeGeometry(transition, layout) {
    const source = layout.positions.get(transition.from);
    const target = layout.positions.get(transition.to);
    if (!source || !target) return null;

    const centerX = position => position.x + NODE_WIDTH / 2;
    if (layout.backEdges.has(transition)) {
        const lane = layout.top - 36 - [...layout.backEdges].indexOf(transition) * 40;
        const sourceX = centerX(source) + 16;
        const targetX = centerX(target) - 16;
        return {
            path: roundedPath([
                {x: sourceX, y: source.y}, {x: sourceX, y: lane},
                {x: targetX, y: lane}, {x: targetX, y: target.y},
            ]),
            labelX: (sourceX + targetX) / 2,
            labelY: lane,
        };
    }
    if (layout.longEdges.includes(transition)) {
        const lane = layout.bottom + 44 + layout.longEdges.indexOf(transition) * 44;
        const sourceX = source.x + NODE_WIDTH + 24;
        const sourceY = source.y + NODE_HEIGHT / 2;
        const targetX = target.x - 24;
        const targetY = target.y + NODE_HEIGHT / 2;
        return {
            path: roundedPath([
                {x: source.x + NODE_WIDTH, y: sourceY}, {x: sourceX, y: sourceY},
                {x: sourceX, y: lane}, {x: targetX, y: lane},
                {x: targetX, y: targetY}, {x: target.x, y: targetY},
            ]),
            labelX: (sourceX + targetX) / 2,
            labelY: lane,
        };
    }

    const startX = source.x + NODE_WIDTH;
    const startY = source.y + NODE_HEIGHT / 2;
    const endX = target.x;
    const endY = target.y + NODE_HEIGHT / 2;
    const middleX = (startX + endX) / 2;
    return {
        path: roundedPath([
            {x: startX, y: startY}, {x: middleX, y: startY},
            {x: middleX, y: endY}, {x: endX, y: endY},
        ]),
        labelX: middleX,
        labelY: (startY + endY) / 2,
    };
}

function wrapTitle(title, maxLength = 20) {
    const lines = [''];
    for (const word of title.split(/\s+/)) {
        const index = lines.length - 1;
        if (lines[index] && (lines[index] + ' ' + word).length > maxLength) {
            lines.push(word);
        } else {
            lines[index] = [lines[index], word].filter(Boolean).join(' ');
        }
    }
    return lines;
}

export function renderProcess(container, template, currentState, lastCommand, labels) {
    container.replaceChildren();
    if (!template || !template.states?.length) {
        const message = document.createElement('p');
        message.className = 'empty';
        message.textContent = 'У выбранного объекта нет шаблона процесса.';
        container.append(message);
        return;
    }

    const layout = layoutProcess(template);
    const svg = svgElement('svg', {
        viewBox: `0 0 ${layout.width} ${layout.height}`,
        role: 'img',
        'aria-label': `${template.title || template.process}. ${currentState ? 'Текущее состояние: ' + labels.state(currentState) : 'Процесс ещё не запущен'}`,
    });
    svg.style.minWidth = `${Math.max(1120, layout.width * 0.85)}px`;
    const definitions = svgElement('defs');
    for (const [id, color] of [['arrow', '#a3afc1'], ['arrow-active', '#218352']]) {
        const marker = svgElement('marker', {
            id, viewBox: '0 0 10 10', refX: 9, refY: 5,
            markerWidth: 8, markerHeight: 8, markerUnits: 'userSpaceOnUse', orient: 'auto-start-reverse',
        });
        marker.append(svgElement('path', {d: 'M 0 0 L 10 5 L 0 10 z', fill: color}));
        definitions.append(marker);
    }
    svg.append(definitions);

    for (const transition of template.transitions || []) {
        const geometry = edgeGeometry(transition, layout);
        if (!geometry) continue;
        const isLast = lastCommand?.result === 'accepted'
            && lastCommand.state_before === transition.from
            && lastCommand.state_after === transition.to
            && lastCommand.command?.action === transition.action;
        const group = svgElement('g', {class: `edge${isLast ? ' last' : ''}`});
        group.append(svgElement('title', {}, `${labels.action(transition.action)} → ${labels.state(transition.to)}\n${labels.condition(transition.condition)}`));
        group.append(svgElement('path', {
            d: geometry.path, class: 'edge-path', 'marker-end': `url(#${isLast ? 'arrow-active' : 'arrow'})`,
        }));
        const labelLines = wrapTitle(labels.action(transition.action), 18);
        const labelWidth = Math.max(...labelLines.map(line => line.length)) * 6 + 18;
        const labelHeight = labelLines.length * 14 + 10;
        group.append(svgElement('rect', {
            x: geometry.labelX - labelWidth / 2, y: geometry.labelY - labelHeight / 2,
            width: labelWidth, height: labelHeight, rx: 6, class: 'edge-label-background',
        }));
        const label = svgElement('text', {
            x: geometry.labelX, y: geometry.labelY - (labelLines.length - 1) * 7 + 4,
            class: 'edge-label', 'text-anchor': 'middle',
        });
        labelLines.forEach((line, index) => label.append(svgElement('tspan', {
            x: geometry.labelX, dy: index ? 14 : 0,
        }, line)));
        group.append(label);
        svg.append(group);
    }

    for (const state of template.states) {
        const {x, y} = layout.positions.get(state.id);
        const group = svgElement('g', {
            class: `node${state.id === currentState ? ' node-current' : ''}`,
            'data-state': state.id,
            transform: `translate(${x} ${y})`,
        });
        group.append(svgElement('title', {}, `${state.title || state.id}${state.id === currentState ? ' · Текущее состояние' : ''}`));
        group.append(svgElement('rect', {width: NODE_WIDTH, height: NODE_HEIGHT, rx: 12, class: 'node-box'}));
        if (state.final) {
            group.append(svgElement('rect', {x: 5, y: 5, width: NODE_WIDTH - 10, height: NODE_HEIGHT - 10, rx: 9, class: 'final-border'}));
        }
        const titleLines = wrapTitle(state.title || state.id, 22);
        const text = svgElement('text', {x: NODE_WIDTH / 2, y: titleLines.length > 1 ? 29 : 37, class: 'node-title', 'text-anchor': 'middle'});
        titleLines.forEach((line, index) => text.append(svgElement('tspan', {x: NODE_WIDTH / 2, dy: index ? 16 : 0}, line)));
        group.append(text);
        group.append(svgElement('text', {x: NODE_WIDTH / 2, y: 68, class: 'node-caption', 'text-anchor': 'middle'},
            state.final ? 'Конец процесса' : state.initial ? 'Начальное состояние' : labels.roles(state.roles)));
        svg.append(group);
        if (state.initial) {
            svg.append(svgElement('circle', {cx: x - 34, cy: y + NODE_HEIGHT / 2, r: 5, fill: '#778399'}));
            svg.append(svgElement('path', {d: `M ${x - 28} ${y + NODE_HEIGHT / 2} H ${x - 3}`, class: 'edge-path', 'marker-end': 'url(#arrow)'}));
        }
    }
    container.append(svg);
}
