package ru.spbsetu.moipis.fsm;

import java.util.Optional;

public interface SnapshotStore {
    Optional<Snapshot> load();
    void save(Snapshot snapshot);
}
