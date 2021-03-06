package speedy.model.database.operators.mainmemory;

import speedy.model.database.IDatabase;
import speedy.model.database.mainmemory.datasource.IntegerOIDGenerator;
import speedy.model.database.mainmemory.datasource.OID;
import speedy.model.database.operators.IOIDGenerator;

public class MainMemoryOIDGenerator implements IOIDGenerator{

    public void initializeOIDs(IDatabase database) {
        //Nothing to do
    }

    public OID getNextOID(String tableName) {
        return IntegerOIDGenerator.getNextOID();
    }

    public void addCounter(String tableName, int size) {
        for (int i = 0; i < size; i++) {
            getNextOID(tableName);
        }
    }

}
