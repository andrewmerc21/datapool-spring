package load.datapool.service;

import load.datapool.db.H2Template;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BaseLockerService implements LockerService {

    private static final List<String> systemSchemas = Arrays.asList("INFORMATION_SCHEMA", "PUBLIC");
    private final HashMap<String, Locker> lockers = new HashMap<>();
    @Autowired
    private H2Template jdbcOperations;

    public BaseLockerService() {
        jdbcOperations = new H2Template();
        initLocks();
        jdbcOperations = null;
    }

    @Override
    public void initLocks() {
        System.out.println("Init start");
        try {
            final String selectSchemas = "SHOW SCHEMAS";
            List<Map<String, Object>> schemas = jdbcOperations.queryForList(selectSchemas);
            schemas.forEach(map -> map.forEach((key, value) -> {
                if (!systemSchemas.contains(value.toString())) {
                    selectTables(value.toString());
                }
            }));
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Init finish");
    }

    private void selectTables(String schema) {
        final String selectTables = "SELECT TABLE_NAME FROM information_schema.tables where table_schema = ?";
        final String[] args = new String[]{schema};
        jdbcOperations.queryForList(selectTables, args, String.class)
                .forEach(tableName -> {
                    selectLocksFromTable(schema, tableName);
                });
    }

    private void selectLocksFromTable(String schema, String tableName) {
        final String fullTableName = TableService.fullName(schema, tableName);
        System.out.println("Table: " + fullTableName);

        if (!containsLockedColumn(schema, tableName))
            return;

        final Integer lockedRows = lockedRows(fullTableName);
        if (lockedRows.equals(0))
            return;

        final int batchRows = 10000;

        final String selectRids = "SELECT rid FROM "+fullTableName+" WHERE rid > ? AND locked = true limit ?";
        final Object[] args = new Object[] {0, batchRows};
        final int ridIndex = 1;

        Locker locker = new BaseLocker(fullTableName, maxRid(fullTableName));
        lockers.put(fullTableName, locker);
        for (int rows = lockedRows; rows > 0; rows -= batchRows) {
            List<Integer> rids = jdbcOperations.queryForList(selectRids, args, Integer.class);
            rids.forEach(locker::lock);
            args[ridIndex] = rids.get(rids.size()-1);
        }

        System.out.println("LockedRows: " + lockedRows);
    }

    private boolean containsLockedColumn(String schema, String tableName) {
        final String lockedColumn = "LOCKED";
        final String selectColumns = "SELECT count(column_name) FROM INFORMATION_SCHEMA.COLUMNS WHERE table_schema = ? AND table_name = ? AND column_name = ?";
        final String[] args = new String[] {schema, tableName, lockedColumn};
        Integer lockedColNum = jdbcOperations.queryForObject(selectColumns, args, Integer.class);
        return lockedColNum > 0;
    }

    private Integer lockedRows(String fullTableName) {
        final String selectCountLocked = "SELECT count(rid) FROM "+fullTableName+" WHERE locked = true";
        return jdbcOperations.queryForObject(selectCountLocked, Integer.class);
    }

    private Integer maxRid(String fullTableName) {
        final String selectMaxRid = "SELECT max(rid) FROM "+fullTableName;
        return jdbcOperations.queryForObject(selectMaxRid, Integer.class);
    }

    private String handlePoolName(String pool) {
        return pool.toUpperCase();
    }

    @Override
    public void putPool(String pool, int size) {
        lockers.put(handlePoolName(pool), new BaseLocker(pool, size));
    }

    @Override
    public void deletePool(String pool) {
        lockers.remove(handlePoolName(pool));
    }

    @Override
    public void lock(String pool, int rid) {
        lockers.get(handlePoolName(pool)).lock(rid);
    }

    @Override
    public void unlock(String pool, int rid) {
        lockers.get(handlePoolName(pool)).unlock(rid);
    }

    @Override
    public int firstUnlockRid(String pool) {
        return lockers.get(handlePoolName(pool)).firstUnlockId();
    }

    @Override
    public int firstBiggerUnlockedId(String pool, int id) {
        return lockers.get(handlePoolName(pool)).firstBiggerUnlockedId(id);
    }
}
