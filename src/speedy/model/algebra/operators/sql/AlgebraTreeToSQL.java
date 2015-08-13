package speedy.model.algebra.operators.sql;

import speedy.SpeedyConstants;
import speedy.model.algebra.operators.IAlgebraTreeVisitor;
import speedy.model.database.AttributeRef;
import speedy.model.database.IDatabase;
import speedy.model.database.ITable;
import speedy.model.database.TableAlias;
import speedy.model.database.dbms.DBMSVirtualTable;
import speedy.model.expressions.Expression;
import speedy.utility.DBMSUtility;
import speedy.utility.SpeedyUtility;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import speedy.model.algebra.aggregatefunctions.AvgAggregateFunction;
import speedy.model.algebra.CartesianProduct;
import speedy.model.algebra.aggregatefunctions.CountAggregateFunction;
import speedy.model.algebra.CreateTableAs;
import speedy.model.algebra.Difference;
import speedy.model.algebra.Distinct;
import speedy.model.algebra.ExtractRandomSample;
import speedy.model.algebra.GroupBy;
import speedy.model.algebra.aggregatefunctions.IAggregateFunction;
import speedy.model.algebra.IAlgebraOperator;
import speedy.model.algebra.Join;
import speedy.model.algebra.Limit;
import speedy.model.algebra.aggregatefunctions.MaxAggregateFunction;
import speedy.model.algebra.aggregatefunctions.MinAggregateFunction;
import speedy.model.algebra.Offset;
import speedy.model.algebra.OrderBy;
import speedy.model.algebra.Partition;
import speedy.model.algebra.Project;
import speedy.model.algebra.RestoreOIDs;
import speedy.model.algebra.Scan;
import speedy.model.algebra.Select;
import speedy.model.algebra.SelectIn;
import speedy.model.algebra.Union;
import speedy.model.algebra.aggregatefunctions.SumAggregateFunction;
import speedy.model.algebra.aggregatefunctions.ValueAggregateFunction;

public class AlgebraTreeToSQL {

    private static Logger logger = LoggerFactory.getLogger(AlgebraTreeToSQL.class);

    public String treeToSQL(IAlgebraOperator root, IDatabase source, IDatabase target, String initialIndent) {
        if (logger.isDebugEnabled()) logger.debug("Generating SQL for algebra \n" + root);
        AlgebraTreeToSQLVisitor visitor = new AlgebraTreeToSQLVisitor(source, target, initialIndent);
        root.accept(visitor);
        if (logger.isDebugEnabled()) logger.debug("Resulting query: \n" + visitor.getResult());
        return visitor.getResult();
    }

    class AlgebraTreeToSQLVisitor implements IAlgebraTreeVisitor {

        private int counter = 0;
        private int indentLevel = 0;
        private SQLQuery result = new SQLQuery();
        private IDatabase source;
        private IDatabase target;
        private String initialIndent;
        private List<String> createTableQueries = new ArrayList<String>();
        private List<String> dropTempTableQueries = new ArrayList<String>();
        private List<AttributeRef> currentProjectionAttribute;

        public AlgebraTreeToSQLVisitor(IDatabase source, IDatabase target, String initialIndent) {
            this.source = source;
            this.target = target;
            this.initialIndent = initialIndent;
        }

        private void visitChildren(IAlgebraOperator operator) {
            List<IAlgebraOperator> listOfChildren = operator.getChildren();
            if (listOfChildren != null) {
                for (IAlgebraOperator child : listOfChildren) {
                    child.accept(this);
                }
            }
        }

        private StringBuilder indentString() {
            StringBuilder indent = new StringBuilder(initialIndent);
            for (int i = 0; i < this.indentLevel; i++) {
                indent.append("    ");
            }
            return indent;
        }

        public String getResult() {
            StringBuilder resultQuery = new StringBuilder();
            for (String query : createTableQueries) {
                resultQuery.append(query);
            }
            resultQuery.append(result);
            for (String query : dropTempTableQueries) {
                resultQuery.append(query);
            }
            return resultQuery.toString();
        }

        public void visitScan(Scan operator) {
            if (logger.isDebugEnabled()) logger.debug("Visiting scan " + operator);
            createSQLSelectClause(operator, new ArrayList<NestedOperator>(), true);
            result.append(" FROM ");
            TableAlias tableAlias = operator.getTableAlias();
            result.append(tableAliasToSQL(tableAlias));
        }

        public void visitSelect(Select operator) {
            IAlgebraOperator child = operator.getChildren().get(0);
            if (child instanceof OrderBy || child instanceof Offset || child instanceof Limit) {
                result.append("SELECT * FROM ");
                generateNestedSelect(child);
            } else {
                visitChildren(operator);
            }
            createWhereClause(operator, false);
        }

        public void visitSelectIn(SelectIn operator) {
            visitChildren(operator);
            result.append("\n").append(this.indentString());
            if (operator.getChildren() != null
                    && (operator.getChildren().get(0) instanceof Select
                    || operator.getChildren().get(0) instanceof Join)) {
                result.append(" AND ");
            } else {
                result.append(" WHERE ");
            }
//            result.append(" WHERE (");
            for (IAlgebraOperator selectionOperator : operator.getSelectionOperators()) {
                result.append("(");
                for (AttributeRef attributeRef : operator.getAttributes(source, target)) {
                    result.append(DBMSUtility.attributeRefToSQLDot(attributeRef)).append(", ");
                }
                SpeedyUtility.removeChars(", ".length(), result.getStringBuilder());
                result.append(") IN (");
                result.append("\n").append(this.indentString());
                indentLevel++;
                selectionOperator.accept(this);
//            operator.getSelectionOperator().accept(this);
                indentLevel--;
                result.append("\n").append(this.indentString());
                result.append(")");
                result.append(" AND ");
            }
            SpeedyUtility.removeChars(" AND ".length(), result.getStringBuilder());
        }

        public void visitJoin(Join operator) {
            List<NestedOperator> nestedSelect = findNestedTablesForJoin(operator);
            createSQLSelectClause(operator, nestedSelect, true);
            result.append(" FROM ");
            IAlgebraOperator leftChild = operator.getChildren().get(0);
            IAlgebraOperator rightChild = operator.getChildren().get(1);
            createJoinClause(operator, leftChild, rightChild, nestedSelect);
        }

        public void visitCartesianProduct(CartesianProduct operator) {
            result.append("SELECT * FROM ");
            for (IAlgebraOperator child : operator.getChildren()) {
                generateNestedSelect(child);
                result.append(", ");
            }
            SpeedyUtility.removeChars(", ".length(), result.getStringBuilder());
        }

        private void generateNestedSelect(IAlgebraOperator operator) {
            this.indentLevel++;
            result.append("(\n");
            operator.accept(this);
            result.append("\n").append(this.indentString()).append(") AS ");
            result.append("Nest_").append(operator.hashCode());
            this.indentLevel--;
        }

        public void visitProject(Project operator) {
            IAlgebraOperator child = operator.getChildren().get(0);
            if (child instanceof Project) {
                //Ignore Project of Project
                child = child.getChildren().get(0);
            }
            if (!(child instanceof Scan) && !(child instanceof Join) && !(child instanceof Select) && !(child instanceof CreateTableAs) && !(child instanceof RestoreOIDs) && !(child instanceof Difference)) {
                throw new IllegalArgumentException("Project of a " + child.getName() + " is not supported");
            }
            child.accept(this);
        }

        public void visitDifference(Difference operator) {
            IAlgebraOperator leftChild = operator.getChildren().get(0);
            leftChild.accept(this);
            result.append("\n").append(this.indentString());
            result.append(" EXCEPT \n");
            IAlgebraOperator rightChild = operator.getChildren().get(1);
            this.indentLevel++;
            rightChild.accept(this);
            this.indentLevel--;
        }

        public void visitUnion(Union operator) {
            throw new UnsupportedOperationException("Not supported yet");
        }

        public void visitOrderBy(OrderBy operator) {
            IAlgebraOperator child = operator.getChildren().get(0);
            child.accept(this);
            result.append("\n").append(this.indentString());
            result.append("ORDER BY ");
            for (AttributeRef attributeRef : operator.getAttributes(source, target)) {
                AttributeRef matchingAttribute = findFirstMatchingAttribute(attributeRef, currentProjectionAttribute);
//            result.append(DBMSUtility.attributeRefToSQLDot(matchingAttribute)).append(", ");
                result.append(DBMSUtility.attributeRefToSQL(matchingAttribute)).append(", ");
            }
            SpeedyUtility.removeChars(", ".length(), result.getStringBuilder());
            result.append("\n");
        }

        public void visitGroupBy(GroupBy operator) {
            result.append(this.indentString());
            result.append("SELECT ");
            if (result.isDistinct()) {
                result.append("DISTINCT ");
                result.setDistinct(false);
            }
            List<NestedOperator> nestedTables = findNestedTablesForGroupBy(operator);
            List<IAggregateFunction> aggregateFunctions = operator.getAggregateFunctions();
            List<String> havingFunctions = extractHavingFunctions(operator);
            for (IAggregateFunction aggregateFunction : aggregateFunctions) {
                AttributeRef attributeRef = aggregateFunction.getAttributeRef();
                if (attributeRef.toString().contains(SpeedyConstants.AGGR + "." + SpeedyConstants.COUNT)) {
                    continue;
                }
                result.append(aggregateFunctionToString(aggregateFunction, aggregateFunction.getAttributeRef(), nestedTables)).append(", ");
            }
            SpeedyUtility.removeChars(", ".length(), result.getStringBuilder());
            result.append("\n").append(this.indentString());
            result.append(" FROM ");
            IAlgebraOperator child = operator.getChildren().get(0);
            if (child instanceof Scan) {
                TableAlias tableAlias = ((Scan) child).getTableAlias();
                result.append(tableAliasToSQL(tableAlias));
            } else if (child instanceof Select) {
                Select select = (Select) child;
                visitSelectForGroupBy(select);
            } else if (child instanceof Join) {
                Join join = (Join) child;
                List<NestedOperator> nestedTablesForJoin = findNestedTablesForJoin(join);
                IAlgebraOperator leftChild = join.getChildren().get(0);
                IAlgebraOperator rightChild = join.getChildren().get(1);
                createJoinClause(join, leftChild, rightChild, nestedTablesForJoin);
            } else if (child instanceof GroupBy) {
                result.append("(\n");
                this.indentLevel++;
                child.accept(this);
                this.indentLevel--;
                result.append("\n").append(this.indentString()).append(") AS ");
//                result.append("Nest_").append(child.hashCode());
                result.append(child.getAttributes(source, target).get(0).getTableName());
            } else {
                throw new IllegalArgumentException("Group by not supported: " + operator);
            }
            result.append("\n").append(this.indentString());
            result.append(" GROUP BY ");
            for (AttributeRef groupingAttribute : operator.getGroupingAttributes()) {
//                result.append(DBMSUtility.attributeRefToSQLDot(groupingAttribute)).append(", ");
                if (containsAlias(nestedTables, groupingAttribute.getTableAlias())) {
                    result.append(DBMSUtility.attributeRefToAliasSQL(groupingAttribute));
                } else {
                    result.append(DBMSUtility.attributeRefToSQLDot(groupingAttribute));
                }
                result.append(", ");
            }
            SpeedyUtility.removeChars(", ".length(), result.getStringBuilder());
            if (!havingFunctions.isEmpty()) {
                result.append("\n").append(this.indentString());
                result.append(" HAVING ");
                for (String havingFunction : havingFunctions) {
                    result.append(havingFunction).append(", ");
                }
                SpeedyUtility.removeChars(", ".length(), result.getStringBuilder());
            }
        }

        public void visitLimit(Limit operator) {
            IAlgebraOperator child = operator.getChildren().get(0);
            child.accept(this);
            result.append("\n").append(this.indentString());
            result.append("LIMIT ").append(operator.getSize());
            result.append("\n");
        }

        public void visitOffset(Offset operator) {
            IAlgebraOperator child = operator.getChildren().get(0);
            child.accept(this);
            result.append("\n").append(this.indentString());
            result.append("OFFSET ").append(operator.getOffset());
            result.append("\n");
        }

        public void visitRestoreOIDs(RestoreOIDs operator) {
            IAlgebraOperator child = operator.getChildren().get(0);
            child.accept(this);
        }

        public void visitCreateTable(CreateTableAs operator) {
            String currentResult = result.toString();
            result = new SQLQuery();
            String tableName = operator.getTableName();
            if (operator.getFather() != null) {
                tableName += "_" + counter++;
            }
            result.append("DROP TABLE IF EXISTS ").append(operator.getSchemaName()).append(".").append(tableName).append(";\n");
            result.append("CREATE TABLE ").append(operator.getSchemaName()).append(".").append(tableName);
            if (operator.isWithOIDs()) {
                result.append(" WITH oids ");
            }
            result.append(" AS (\n");
            IAlgebraOperator child = operator.getChildren().get(0);
            this.indentLevel++;
            child.accept(this);
            this.indentLevel--;
            result.append(");").append("\n");
            String createTableQuery = result.toString();
            this.createTableQueries.add(createTableQuery);
            result = new SQLQuery(currentResult);
            if (operator.getFather() != null) {
                if (operator.getFather() instanceof Join) {
                    result.append(operator.getSchemaName()).append(".").append(tableName).append(" AS ").append(operator.getTableAlias());
                } else if (operator.getFather() instanceof Project) {
                    createSQLSelectClause(operator, new ArrayList<NestedOperator>(), false);
                    result.append(" FROM ").append(operator.getSchemaName()).append(".").append(tableName).append(" AS ").append(operator.getTableAlias());
                } else {
                    throw new IllegalArgumentException("Create table is allowed only on Join or Project");
                }
            }
        }

        public void visitDistinct(Distinct operator) {
            result.setDistinct(true);
            IAlgebraOperator child = operator.getChildren().get(0);
            child.accept(this);
        }

        ///////////////////////////////////////////////////////////
        private void createSQLSelectClause(IAlgebraOperator operator, List<NestedOperator> nestedSelect, boolean useTableName) {
            result.append(this.indentString());
            result.append("SELECT ");
            if (result.isDistinct()) {
                result.append("DISTINCT ");
                result.setDistinct(false);
            }
            this.indentLevel++;
            List<AttributeRef> attributes = operator.getAttributes(source, target);
            List<IAggregateFunction> aggregateFunctions = null;
            List<AttributeRef> newAttributes = null;
            IAlgebraOperator father = operator.getFather();
            if (father != null && (father instanceof Select)) {
                father = father.getFather();
            }
            if (father != null && (father instanceof Project)) {
                Project project = (Project) father;
                if (!project.isAggregative()) {
                    attributes = project.getAttributes(source, target);
                    aggregateFunctions = null;
                } else {
                    attributes = null;
                    aggregateFunctions = project.getAggregateFunctions();
                }
                newAttributes = project.getNewAttributes();
            }
            this.currentProjectionAttribute = attributes;
            result.append("\n").append(this.indentString());
            result.append(attributesToSQL(attributes, aggregateFunctions, newAttributes, nestedSelect, useTableName));
            this.indentLevel--;
            result.append("\n").append(this.indentString());
        }

        private void createJoinClausePart(IAlgebraOperator operator, List<NestedOperator> nestedSelect) {
            if ((operator instanceof Join)) {
                IAlgebraOperator leftChild = operator.getChildren().get(0);
                IAlgebraOperator rightChild = operator.getChildren().get(1);
                createJoinClause((Join) operator, leftChild, rightChild, nestedSelect);
            } else if ((operator instanceof Scan)) {
                TableAlias tableAlias = ((Scan) operator).getTableAlias();
                result.append(tableAliasToSQL(tableAlias));
            } else if ((operator instanceof Select)) {
                IAlgebraOperator child = operator.getChildren().get(0);
                createJoinClausePart(child, nestedSelect);
//            Select select = (Select) operator;
//            createWhereClause(select);
            } else if ((operator instanceof Project)) {
                result.append("(\n");
                this.indentLevel++;
                operator.accept(this);
                this.indentLevel--;
                result.append("\n").append(this.indentString()).append(") AS ");
                result.append(generateNestedAlias(operator));
            } else if ((operator instanceof GroupBy)) {
                result.append("(\n");
                this.indentLevel++;
                operator.accept(this);
                this.indentLevel--;
                result.append("\n").append(this.indentString()).append(") AS ");
                result.append(generateNestedAlias(operator));
            } else if ((operator instanceof Difference)) {
                result.append("(\n");
                this.indentLevel++;
                operator.accept(this);
                this.indentLevel--;
                result.append("\n").append(this.indentString()).append(") AS ");
                result.append("Nest_").append(operator.hashCode());
            } else if ((operator instanceof CreateTableAs)) {
                this.indentLevel++;
                operator.accept(this);
                this.indentLevel--;
            } else {
                throw new IllegalArgumentException("Join not supported: " + operator);
            }
        }

        private void createJoinClause(Join operator, IAlgebraOperator leftOperator, IAlgebraOperator rightOperator, List<NestedOperator> nestedSelects) {
            createJoinClausePart(leftOperator, nestedSelects);
            result.append(" JOIN ");
            createJoinClausePart(rightOperator, nestedSelects);
            result.append(" ON ");
            List<AttributeRef> leftAttributes = operator.getLeftAttributes();
            List<AttributeRef> rightAttributes = operator.getRightAttributes();
            for (int i = 0; i < leftAttributes.size(); i++) {
                AttributeRef leftAttribute = leftAttributes.get(i);
                result.append(getJoinAttributeSQL(leftAttribute, leftOperator, nestedSelects));
                result.append(" = ");
                AttributeRef rightAttribute = rightAttributes.get(i);
                result.append(getJoinAttributeSQL(rightAttribute, rightOperator, nestedSelects));
                result.append(" AND ");
            }
            SpeedyUtility.removeChars(" AND ".length(), result.getStringBuilder());
            if (leftOperator instanceof Select) {
                createWhereClause((Select) leftOperator, true);
            }
            if (rightOperator instanceof Select) {
                createWhereClause((Select) rightOperator, true);
            }
        }

        private String getJoinAttributeSQL(AttributeRef attribute, IAlgebraOperator operator, List<NestedOperator> nestedSelects) {
            boolean useAlias = false;
            if (operator instanceof CreateTableAs) {
                useAlias = true;
            }
            if (operator instanceof Join && (operator.getChildren().get(0) instanceof CreateTableAs)
                    && (operator.getChildren().get(1) instanceof CreateTableAs)) {
                useAlias = true;
            }
            IAlgebraOperator nestedOperator = findNestedOperator(nestedSelects, operator, attribute.getTableAlias());
            if (nestedOperator != null) {
                useAlias = true;
            }
            String attributeResult;
            if (useAlias) {
                attributeResult = DBMSUtility.attributeRefToAliasSQL(attribute);
            } else {
                attributeResult = DBMSUtility.attributeRefToSQLDot(attribute);
            }
            if (logger.isDebugEnabled()) logger.debug(" Attribute: " + attribute);
            if (logger.isDebugEnabled()) logger.debug(" Operator: " + operator);
            if (logger.isDebugEnabled()) logger.debug(" NestedOperator: " + nestedOperator);
            if (logger.isDebugEnabled()) logger.debug(" Result: " + attributeResult);
            return attributeResult;
        }

        private void createWhereClause(Select operator, boolean append) {
            if (operator.getChildren() != null && operator.getChildren().get(0) instanceof GroupBy) {
                return; //HAVING
            }
            result.append("\n").append(this.indentString());
            if (append || operator.getChildren() != null
                    && (operator.getChildren().get(0) instanceof Select
                    || operator.getChildren().get(0) instanceof Join)) {
                result.append(" AND ");
            } else {
                result.append(" WHERE ");
            }
            this.indentLevel++;
            result.append("\n").append(this.indentString());
            for (Expression condition : operator.getSelections()) {
                boolean useAlias = true;
                if (isInACartesianProduct(operator)) {
                    useAlias = false;
                }
                if (!operator.getChildren().isEmpty()) {
                    IAlgebraOperator firstChild = operator.getChildren().get(0);
                    if (firstChild instanceof Difference) {
                        Difference diff = (Difference) operator.getChildren().get(0);
                        if (diff.getChildren().get(0) instanceof Difference || diff.getChildren().get(1) instanceof Difference) {
                            useAlias = false;
                        }
                    }
                }
                String expressionSQL = DBMSUtility.expressionToSQL(condition, useAlias);
                result.append(expressionSQL);
                result.append(" AND ");
            }
            SpeedyUtility.removeChars(" AND ".length(), result.getStringBuilder());
            this.indentLevel--;
        }

        private boolean isInACartesianProduct(IAlgebraOperator operator) {
            if (operator.getChildren().isEmpty()) {
                return false;
            }
            IAlgebraOperator firstChild = operator.getChildren().get(0);
            if (firstChild instanceof CartesianProduct) {
                return true;
            }
            if (firstChild instanceof Select) {
                return isInACartesianProduct(firstChild);
            }
            return false;
        }

        private List<NestedOperator> findNestedTablesForJoin(IAlgebraOperator operator) {
            List<NestedOperator> attributes = new ArrayList<NestedOperator>();
            IAlgebraOperator leftChild = operator.getChildren().get(0);
            for (AttributeRef nestedAttribute : getNestedAttributes(leftChild)) {
                NestedOperator nestedOperator = new NestedOperator(leftChild, nestedAttribute.getTableAlias());
                attributes.add(nestedOperator);
            }
            IAlgebraOperator rightChild = operator.getChildren().get(1);
            for (AttributeRef nestedAttribute : getNestedAttributes(rightChild)) {
                NestedOperator nestedOperator = new NestedOperator(rightChild, nestedAttribute.getTableAlias());
                attributes.add(nestedOperator);
            }
            List<NestedOperator> tableAliases = new ArrayList<NestedOperator>();
            for (NestedOperator nestedOperator : attributes) {
                if (containsAlias(tableAliases, nestedOperator.alias)) {
                    continue;
                }
                tableAliases.add(nestedOperator);
            }
            if (logger.isDebugEnabled()) logger.debug("Nested tables for operator:\n" + operator + "\n" + tableAliases);
            return tableAliases;
        }

        private List<NestedOperator> findNestedTablesForGroupBy(GroupBy operator) {
            List<NestedOperator> tableAliases = new ArrayList<NestedOperator>();
            List<AttributeRef> attributes = new ArrayList<AttributeRef>();
            IAlgebraOperator child = operator.getChildren().get(0);
            attributes.addAll(getNestedAttributes(child));
            for (AttributeRef attributeRef : attributes) {
                if (containsAlias(tableAliases, attributeRef.getTableAlias())) {
                    continue;
                }
                NestedOperator nestedOperator = new NestedOperator(operator, attributeRef.getTableAlias());
                tableAliases.add(nestedOperator);
            }
            return tableAliases;
        }

        private List<AttributeRef> getNestedAttributes(IAlgebraOperator operator) {
            List<AttributeRef> attributes = new ArrayList<AttributeRef>();
            if (operator instanceof Difference) {
                attributes.addAll(operator.getAttributes(source, target));
            }
            if (operator instanceof GroupBy) {
                attributes.addAll(operator.getAttributes(source, target));
            }
            if (operator instanceof Project) {
                attributes.addAll(operator.getAttributes(source, target));
            }
            if (operator instanceof Join) {
                IAlgebraOperator leftChild = operator.getChildren().get(0);
                attributes.addAll(getNestedAttributes(leftChild));
                IAlgebraOperator rightChild = operator.getChildren().get(1);
                attributes.addAll(getNestedAttributes(rightChild));
//            attributes.addAll(operator.getAttributes(source, target));
            }
            if (operator instanceof CreateTableAs) {
                attributes.addAll(operator.getAttributes(source, target));
//            CreateTableAs createTable = (CreateTableAs)operator;
//            for (AttributeRef attributeRef : operator.getAttributes(source, target)) {
//                attributes.add(new AttributeRef(createTable.getTableAlias(), attributeRef.getName()));
//            }
            }
            if (operator instanceof Join) {
                IAlgebraOperator leftChild = operator.getChildren().get(0);
                attributes.addAll(getNestedAttributes(leftChild));
                IAlgebraOperator rightChild = operator.getChildren().get(1);
                attributes.addAll(getNestedAttributes(rightChild));
//            attributes.addAll(operator.getAttributes(source, target));
            }
            return attributes;
        }

        private String generateNestedAlias(IAlgebraOperator operator) {
            if (operator instanceof Scan) {
                TableAlias tableAlias = ((Scan) operator).getTableAlias();
                return tableAlias.getTableName();
            } else if (operator instanceof Select) {
                Select select = (Select) operator;
                operator = select.getChildren().get(0);
                if (operator instanceof Scan) {
                    TableAlias tableAlias = ((Scan) operator).getTableAlias();
                    return tableAlias.getTableName();
                }
            }
            IAlgebraOperator child = operator.getChildren().get(0);
            if (child != null) {
                return generateNestedAlias(child);
            }
            return "Nest_" + operator.hashCode();
        }

        private void visitSelectForGroupBy(Select operator) {
            IAlgebraOperator child = operator.getChildren().get(0);
            if (child instanceof Scan) {
                TableAlias tableAlias = ((Scan) child).getTableAlias();
                result.append(tableAliasToSQL(tableAlias));
            } else {
                throw new IllegalArgumentException("Group by not supported: " + operator);
            }
            result.append("\n").append(this.indentString());
            result.append(" WHERE  ");
            this.indentLevel++;
            result.append("\n").append(this.indentString());
            for (Expression condition : operator.getSelections()) {
                result.append(DBMSUtility.expressionToSQL(condition));
                result.append(" AND ");
            }
            SpeedyUtility.removeChars(" AND ".length(), result.getStringBuilder());
            this.indentLevel--;
        }

        @SuppressWarnings("unchecked")
        private List<String> extractHavingFunctions(GroupBy operator) {
            if (!(operator.getFather() instanceof Select)) {
                return Collections.EMPTY_LIST;
            }
            Select select = (Select) operator.getFather();
            if (select.getSelections().size() != 1) {
                return Collections.EMPTY_LIST;
            }
            Expression expression = select.getSelections().get(0).clone();
            if (!expression.toString().contains(SpeedyConstants.AGGR + "." + SpeedyConstants.COUNT)) {
                return Collections.EMPTY_LIST;
            }
            List<String> havingFunctions = new ArrayList<String>();
            if (expression.toString().contains("count")) {
                havingFunctions.add(getCountHavingSQL(expression));
            } else {
                throw new IllegalArgumentException("Having function " + expression + " is not yet supported!");
            }
            return havingFunctions;
        }

        private String getCountHavingSQL(Expression expression) {
            String variableName = expression.getVariables().get(0);
            String expressionString = expression.toString();
            expressionString = expressionString.replace(variableName, "count(*) ");
            return expressionString;
        }

        private String attributesToSQL(List<AttributeRef> attributes, List<IAggregateFunction> aggregateFunctions,
                List<AttributeRef> newAttributes, List<NestedOperator> nestedSelect, boolean useTableName) {
            if (logger.isDebugEnabled()) logger.debug("Generating SQL for attributes\n\nAttributes: " + attributes + "\n\t" + newAttributes + "\n\tNested Select: " + nestedSelect + "\n\tuseTableName: " + useTableName);
            StringBuilder sb = new StringBuilder();
            if (attributes != null) {
                List<String> sqlAttributes = new ArrayList<String>();
                for (int i = 0; i < attributes.size(); i++) {
                    AttributeRef newAttributeRef = null;
                    if (newAttributes != null) {
                        newAttributeRef = newAttributes.get(i);
                    }
                    String attribute = attributeToSQL(attributes.get(i), useTableName, nestedSelect, newAttributeRef);
                    if (!sqlAttributes.contains(attribute)) {
                        sqlAttributes.add(attribute);
                    }
                }
                for (String attribute : sqlAttributes) {
                    sb.append(attribute).append(",\n").append(this.indentString());
                }
                SpeedyUtility.removeChars(",\n".length() + this.indentString().length(), sb);
            } else {
                for (int i = 0; i < aggregateFunctions.size(); i++) {
                    AttributeRef newAttributeRef = null;
                    if (newAttributes != null) {
                        newAttributeRef = newAttributes.get(i);
                    }
                    IAggregateFunction aggregateFunction = aggregateFunctions.get(i);
                    AttributeRef attributeRef = aggregateFunction.getAttributeRef();
                    if (attributeRef.toString().contains(SpeedyConstants.AGGR + "." + SpeedyConstants.COUNT)) {
                        continue;
                    }
                    if (newAttributeRef == null) {
                        newAttributeRef = aggregateFunction.getAttributeRef();
                    }
                    sb.append(aggregateFunctionToString(aggregateFunction, newAttributeRef, null));
                    sb.append(", ");
                }
                SpeedyUtility.removeChars(", ".length(), sb);
            }
            return sb.toString();
        }

        public String attributeToSQL(AttributeRef attributeRef, boolean useTableName, List<NestedOperator> nestedSelects, AttributeRef newAttributeRef) {
            StringBuilder sb = new StringBuilder();
//            if (!useTableName || containsAlias(nestedSelects, attributeRef.getTableAlias())) {
            if (!useTableName || containsNestedAttribute(nestedSelects, attributeRef)) {
                sb.append(DBMSUtility.attributeRefToAliasSQL(attributeRef));
            } else {
                sb.append(DBMSUtility.attributeRefToSQLDot(attributeRef));
            }
            if (newAttributeRef != null) {
                sb.append(" AS ");
                sb.append(newAttributeRef.getName());
//            } else if (!(containsAlias(nestedSelects, attributeRef.getTableAlias()))) {
            } else if (!(containsNestedAttribute(nestedSelects, attributeRef))) {
                sb.append(" AS ");
                sb.append(DBMSUtility.attributeRefToAliasSQL(attributeRef));
            }
            return sb.toString();
        }

        private boolean containsNestedAttribute(List<NestedOperator> nestedSelects, AttributeRef attribute) {
            for (NestedOperator nestedSelect : nestedSelects) {
                if (!nestedSelect.alias.equals(attribute.getTableAlias())) {
                    continue;
                }
                IAlgebraOperator operator = nestedSelect.operator;
                return operator.getAttributes(source, target).contains(attribute);
            }
            return false;
        }

        private boolean containsAlias(List<NestedOperator> nestedSelects, TableAlias alias) {
            for (NestedOperator nestedSelect : nestedSelects) {
                if (nestedSelect.alias.equals(alias)) {
                    return true;
                }
            }
            return false;
        }

        private IAlgebraOperator findNestedOperator(List<NestedOperator> nestedSelects, IAlgebraOperator operator, TableAlias alias) {
            for (NestedOperator nestedSelect : nestedSelects) {
                if (nestedSelect.alias.equals(alias) && nestedSelect.operator.equals(operator)) {
//                if (nestedSelect.operator.equals(operator)) {
                    return nestedSelect.operator;
                }
            }
            return null;
        }

        private String tableAliasToSQL(TableAlias tableAlias) {
            StringBuilder sb = new StringBuilder();
            ITable table;
            if (tableAlias.isSource()) {
                table = source.getTable(tableAlias.getTableName());
            } else {
                table = target.getTable(tableAlias.getTableName());
            }
            sb.append(table.toShortString());
            if (tableAlias.isAliased() || tableAlias.isSource() || (table instanceof DBMSVirtualTable)) {
                sb.append(" AS ").append(DBMSUtility.tableAliasToSQL(tableAlias));
            }
            return sb.toString();
        }

        private AttributeRef findFirstMatchingAttribute(AttributeRef originalAttribute, List<AttributeRef> attributes) {
            for (AttributeRef attribute : attributes) {
                if (attribute.getTableName().equalsIgnoreCase(originalAttribute.getTableName()) && attribute.getName().equalsIgnoreCase(originalAttribute.getName())) {
                    return attribute;
                }
            }
            throw new IllegalArgumentException("Unable to find attribute " + originalAttribute + " into " + attributes);
        }

        private String aggregateFunctionToString(IAggregateFunction aggregateFunction, AttributeRef newAttribute, List<NestedOperator> nestedTables) {
            if (aggregateFunction instanceof ValueAggregateFunction) {
                return attributeToSQL(aggregateFunction.getAttributeRef(), true, nestedTables, null);
            }
            if (aggregateFunction instanceof MaxAggregateFunction) {
                return "max(" + aggregateFunction.getAttributeRef() + ") as " + DBMSUtility.attributeRefToAliasSQL(newAttribute);
            }
            if (aggregateFunction instanceof MinAggregateFunction) {
                return "min(" + aggregateFunction.getAttributeRef() + ") as " + DBMSUtility.attributeRefToAliasSQL(newAttribute);
            }
            if (aggregateFunction instanceof AvgAggregateFunction) {
                return "avg(" + aggregateFunction.getAttributeRef() + ") as " + DBMSUtility.attributeRefToAliasSQL(newAttribute);
            }
            if (aggregateFunction instanceof SumAggregateFunction) {
                return "sum(" + aggregateFunction.getAttributeRef() + ") as " + DBMSUtility.attributeRefToAliasSQL(newAttribute);
            }
            if (aggregateFunction instanceof CountAggregateFunction) {
                return "count(*) as " + DBMSUtility.attributeRefToAliasSQL(aggregateFunction.getAttributeRef());
            }
            throw new UnsupportedOperationException("Unable generate SQL for aggregate function" + aggregateFunction);
        }

        public void visitExtractRandomSample(ExtractRandomSample operator) {
            long rangeMin = operator.getFloor();
            long rangeMax = operator.getCeil();
            long sampleSize = operator.getSampleSize();
            result.append(this.indentString());
            this.indentLevel++;
            result.append("SELECT * FROM (").append("\n");
            this.indentLevel++;
            result.append(this.indentString());
            result.append("SELECT * FROM (").append("\n");
            this.indentLevel++;
            result.append(this.indentString());
            result.append("SELECT DISTINCT ").append(rangeMin).append(" + floor(random() * ");
            result.append(rangeMax).append(")::integer AS oid").append("\n");
            result.append(this.indentString());
            result.append("FROM generate_series(1," + sampleSize + ") g").append("\n");
            this.indentLevel--;
            result.append(this.indentString());
            result.append(") as r").append("\n");
            result.append(this.indentString());
            result.append("JOIN (").append("\n");
            this.indentLevel++;
            visitChildren(operator);
            result.append("\n");
            this.indentLevel--;
            result.append(this.indentString());
            AttributeRef oidAttribute = SpeedyUtility.getFirstOIDAttribute(operator.getAttributes(source, target));
            if (oidAttribute == null) {
                throw new IllegalArgumentException("ExtractRandomSample operator has a child without OID." + operator);
            }
        }

        public void visitPartition(Partition operator) {
            throw new UnsupportedOperationException("Not supported yet."); //TODO Implement method
        }

    }

    class NestedOperator {

        IAlgebraOperator operator;
        TableAlias alias;

        public NestedOperator(IAlgebraOperator operator, TableAlias alias) {
            this.operator = operator;
            this.alias = alias;
        }

        @Override
        public String toString() {
            return alias.toString();
        }

    }
}