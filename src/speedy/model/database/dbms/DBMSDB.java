package speedy.model.database.dbms;

import speedy.SpeedyConstants;
import speedy.model.database.ForeignKey;
import speedy.model.database.IDatabase;
import speedy.model.database.ITable;
import speedy.model.database.Key;
import speedy.model.database.operators.dbms.ExecuteInitDB;
import speedy.persistence.relational.AccessConfiguration;
import speedy.utility.DBMSUtility;
import java.util.ArrayList;
import java.util.List;

public class DBMSDB implements IDatabase {

    private static ExecuteInitDB initDBExecutor = new ExecuteInitDB();
    private AccessConfiguration accessConfiguration;
    private List<String> tableNames;
    private List<Key> keys;
    private List<ForeignKey> foreignKeys;
    private boolean initialized = false;
    private List<DBMSTable> tables = new ArrayList<DBMSTable>();
    private InitDBConfiguration initDBConfiguration = new InitDBConfiguration();

    public DBMSDB(AccessConfiguration accessConfiguration) {
        this.accessConfiguration = accessConfiguration;
    }

    public void initDBMS() {
        if (initialized) {
            return;
        }
        if (!DBMSUtility.isDBExists(accessConfiguration)) {
            DBMSUtility.createDB(accessConfiguration);
        }
        if (!initDBConfiguration.isEmpty() && DBMSUtility.isSchemaEmpty(accessConfiguration)) {
            initDBExecutor.execute(this);
        }
        initialized = true;
        loadTables();
    }

    private void loadTables() {
        for (String tableName : getTableNames()) {
            tables.add(new DBMSTable(tableName, accessConfiguration));
        }
    }

    public void addTable(ITable table) {
        tables.add((DBMSTable) table);
        if (!getTableNames().contains(table.getName())) {
            tableNames.add(table.getName());
        }
    }

    public String getName() {
        return this.accessConfiguration.getSchemaName();
    }

    public List<String> getTableNames() {
        initDBMS();
        if (tableNames == null) {
            tableNames = DBMSUtility.loadTableNames(accessConfiguration);
        }
        return tableNames;
    }

    public List<Key> getKeys() {
        initDBMS();
        if (keys == null) {
            keys = DBMSUtility.loadKeys(accessConfiguration);
        }
        return keys;
    }

    public List<Key> getKeys(String table) {
        List<Key> result = new ArrayList<Key>();
        for (Key key : getKeys()) {
            String tableName = key.getAttributes().get(0).getTableName();
            if (tableName.equals(table)) {
                result.add(key);
            }
        }
        return result;
    }

    public List<ForeignKey> getForeignKeys() {
        initDBMS();
        if (foreignKeys == null) {
            foreignKeys = DBMSUtility.loadForeignKeys(accessConfiguration);
        }
        return foreignKeys;
    }

    public List<ForeignKey> getForeignKeys(String table) {
        List<ForeignKey> result = new ArrayList<ForeignKey>();
        for (ForeignKey foreignKey : getForeignKeys()) {
            String tableName = foreignKey.getRefAttributes().get(0).getTableName();
            if (tableName.equals(table)) {
                result.add(foreignKey);
            }
        }
        return result;
    }

    public ITable getTable(String name) {
//        return new DBMSTable(name, accessConfiguration);
        initDBMS();
        for (DBMSTable table : tables) {
            if (table.getName().equalsIgnoreCase(name)) {
                return table;
            }
        }
        throw new IllegalArgumentException("Unable to find table " + name + " in database " + printSchema());
    }

    public ITable getFirstTable() {
        return getTable(getTableNames().get(0));
    }

    public AccessConfiguration getAccessConfiguration() {
        return accessConfiguration;
    }

    public InitDBConfiguration getInitDBConfiguration() {
        return initDBConfiguration;
    }

    public long getSize() {
        long size = 0;
        for (String tableName : tableNames) {
            DBMSTable table = (DBMSTable) getTable(tableName);
            size += table.getSize();
        }
        return size;
    }

    public IDatabase clone() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public String printSchema() {
        StringBuilder result = new StringBuilder();
        result.append("Schema: ").append(getName()).append(" {\n");
        for (String tableName : getTableNames()) {
            DBMSTable table = (DBMSTable) getTable(tableName);
            result.append(table.printSchema(SpeedyConstants.INDENT));
//            table.closeConnection();
        }
        if (!getKeys().isEmpty()) {
            result.append(SpeedyConstants.INDENT).append("--------------- Keys: ---------------\n");
            for (Key key : getKeys()) {
                result.append(SpeedyConstants.INDENT).append(key).append("\n");
            }
        }
        if (!getForeignKeys().isEmpty()) {
            result.append(SpeedyConstants.INDENT).append("----------- Foreign Keys: -----------\n");
            for (ForeignKey foreignKey : getForeignKeys()) {
                result.append(SpeedyConstants.INDENT).append(foreignKey).append("\n");
            }
        }
        result.append("}\n");
        return result.toString();
    }

    public String printInstances() {
        return printInstances(false);
    }

    public String printInstances(boolean sort) {
        StringBuilder result = new StringBuilder();
        for (String tableName : getTableNames()) {
            DBMSTable table = (DBMSTable) getTable(tableName);
            if (sort) {
                result.append(table.toStringWithSort(SpeedyConstants.INDENT));
            } else {
                result.append(table.toString(SpeedyConstants.INDENT));
            }
//            table.closeConnection();
        }
        return result.toString();
    }

    public String toString() {
        StringBuilder result = new StringBuilder();
//        result.append(printSchema());
        result.append(printInstances());
        return result.toString();
    }
}
