package ru.spbsetu.moipis.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.spbsetu.moipis.config.YamlConfiguration;
import ru.spbsetu.moipis.fsm.Snapshot;
import ru.spbsetu.moipis.fsm.SnapshotStore;

import java.util.Optional;

@Repository
public class JdbcSnapshotStore implements SnapshotStore {
    private final JdbcTemplate jdbc;
    private final YamlConfiguration yaml;

    public JdbcSnapshotStore(JdbcTemplate jdbc, YamlConfiguration yaml) {
        this.jdbc = jdbc;
        this.yaml = yaml;
    }

    @Override
    public Optional<Snapshot> load() {
        return jdbc.query("SELECT configuration FROM automaton WHERE id = 1",
                (row, index) -> yaml.read(row.getString(1))).stream().findFirst();
    }

    @Override
    @Transactional
    public void save(Snapshot snapshot) {
        var configuration = yaml.write(snapshot);
        if (jdbc.update("UPDATE automaton SET configuration = ? WHERE id = 1", configuration) == 0) {
            jdbc.update("INSERT INTO automaton (id, configuration) VALUES (1, ?)", configuration);
        }
    }
}
