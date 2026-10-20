CREATE TABLE IF NOT EXISTS automaton (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    configuration TEXT NOT NULL
);
