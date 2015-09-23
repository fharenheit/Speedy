package speedy.test;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import speedy.OperatorFactory;
import speedy.model.algebra.Scan;
import speedy.model.algebra.Select;
import speedy.model.algebra.operators.ITupleIterator;
import speedy.model.database.AttributeRef;
import speedy.model.database.TableAlias;
import speedy.model.database.dbms.DBMSDB;
import speedy.model.database.operators.IRunQuery;
import speedy.model.expressions.Expression;
import speedy.persistence.DAODBMSDatabase;
import speedy.persistence.file.CSVFile;
import speedy.persistence.relational.QueryStatManager;
import speedy.test.utility.UtilityForTests;
import speedy.utility.SpeedyUtility;

public class TestDBMSCSV {

    private static Logger logger = LoggerFactory.getLogger(TestDBMSCSV.class);

    private DBMSDB database;
    private IRunQuery queryRunner;

    @Before
    public void setUp() {
        DAODBMSDatabase daoDatabase = new DAODBMSDatabase();
        String driver = "org.postgresql.Driver";
        String uri = "jdbc:postgresql:speedy_employees";
        String schema = "target";
        String login = "pguser";
        String password = "pguser";
        database = daoDatabase.loadDatabase(driver, uri, schema, login, password);
        database.getInitDBConfiguration().setCreateTablesFromFiles(true);
        CSVFile fileToImport = new CSVFile(UtilityForTests.getAbsoluteFileName("/resources/employees/csv/50_emp.csv"));
        fileToImport.setSeparator(',');
        database.getInitDBConfiguration().addFileToImportForTable("emp", fileToImport);
//        UtilityForTests.deleteDB(database.getAccessConfiguration());
        queryRunner = OperatorFactory.getInstance().getQueryRunner(database);
    }

    @After
    public void tearDown() {
        UtilityForTests.deleteDB(database.getAccessConfiguration());
    }

    @Test
    public void testScan() {
        TableAlias tableAlias = new TableAlias("emp");
        Scan scan = new Scan(tableAlias);
        if (logger.isDebugEnabled()) logger.debug(scan.toString());
        ITupleIterator result = queryRunner.run(scan, null, database);
        String stringResult = SpeedyUtility.printTupleIterator(result);
        if (logger.isDebugEnabled()) logger.debug(stringResult);
        result.close();
        Assert.assertTrue(stringResult.startsWith("Number of tuples: 50\n"));
    }

    @Test
    public void testSelect() {
        TableAlias tableAlias = new TableAlias("emp");
        Scan scan = new Scan(tableAlias);
        Expression expression = new Expression("salary > 3000");
        expression.changeVariableDescription("salary", new AttributeRef(tableAlias, "salary"));
        Select select = new Select(expression);
        select.addChild(scan);
        if (logger.isDebugEnabled()) logger.debug(select.toString());
        ITupleIterator result = queryRunner.run(select, null, database);
        String stringResult = SpeedyUtility.printTupleIterator(result);
        if (logger.isDebugEnabled()) logger.debug(stringResult);
        result.close();
        Assert.assertTrue(stringResult.startsWith("Number of tuples: 24\n"));
        QueryStatManager.getInstance().printStatistics();
    }
}
