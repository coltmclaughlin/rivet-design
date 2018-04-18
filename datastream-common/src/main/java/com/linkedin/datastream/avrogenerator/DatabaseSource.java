package com.linkedin.datastream.avrogenerator;

import java.sql.SQLException;
import java.util.List;

import org.apache.avro.Schema;
import org.jetbrains.annotations.NotNull;

/**
 * The Schema generator operates by making queries to the targeted database in order to
 * grab information about the Database Field Types.
 *
 * Implementations of this interface are responsible for all communication to the Database.
 */
public abstract class DatabaseSource {

  /**
   * Initialize connection to the database
   */
  public void initializeConnection() throws SQLException {
    // default implementation is no-op
  }

  /**
   * Close connection to the database
   */
  public void closeConnection() {
    // default implementation is no-op
  }

  /**
   * Determine if the arguments points to a table or view in the Database
   * @return true is exists, false otherwise
   */
  public abstract boolean isTable(String schemaName, String tableName);

  /**
   * Determine if the arguments point to a Collection type in the table
   * @return true is collection, false otherwise
   */
  public abstract boolean isCollection(String schemaName, String fieldTypeName) throws SQLException;

  /**
   * Determine if the arguments point to Struct Type in the table
   * @return true if Struct, false otherwise
   */
  public abstract boolean isStruct(String schemaName, String fieldTypeName) throws SQLException;

  /**
   * Determine if the fieldTypeName is one of the primitive types of the Database
   * @return true if primitive
   */
  public abstract boolean isPrimitive(String fieldTypeName) throws SQLException;

  /**
   * Retrieve the list of tables in the specified Database.
   * @return List of tables in the Database
   * @throws SQLException
   */
  public List<String> getAllTablesInDatabase() throws SQLException {
    throw new UnsupportedOperationException("getAllTablesInDatabase operation not supported");
  }

  /**
   * Retrieve the table Metadata containing information such as all of Column names
   * and their types.
   */
  public abstract List<TableMetadata> getTableMetadata(String schemaName, String tableName) throws SQLException;

  /**
   * Retrieve the metadata of the Struct type. Contains information about the child columns that are
   * associated with this Struct Type
   */
  public abstract List<StructMetadata> getStructMetadata(String schemaName, String fieldTypeName) throws SQLException;

  /**
   * Retrieve the metadata of the Collection Type. Contains information about the elements of the
   * Array Type
   */
  public abstract CollectionMetadata getCollectionMetadata(String schemaName, String fieldTypeName) throws SQLException;

  /**
   * Create and run a query to get all primary keys from the Database table/view
   */
  public abstract List<String> getPrimaryKeyFields(String tableName) throws SQLException, SchemaGenerationException;

  /**
   * Create and execute a query to get all the fields from the Database table/view
   */
  public abstract List<String> getAllFields(String tableName, String dbName) throws SQLException;

  /**
   * Get the avro schema for the table
   * @param tableName
   * @return Schema for the table or null if operation not supported
   */
  public Schema getTableSchema(String tableName) {
    return null;
  }


  public static class CollectionMetadata {
    private String _fieldName;
    private String _schemaName;
    private int _precision;
    private int _scale;

    public CollectionMetadata(@NotNull String schemaName, @NotNull  String fieldName, int precision, int scale) {
      _fieldName = fieldName;
      _schemaName = schemaName;
      _precision = precision;
      _scale = scale;
    }

    public String getElementFieldTypeName() {
      return _fieldName;
    }

    public String getElementSchemaName() {
      return _schemaName;
    }

    public int getElementPrecision() {
      return _precision;
    }

    public int getElementScale() {
      return _scale;
    }
  }


  public static class StructMetadata {
    private String _fieldTypeName;
    private String _schemaName;
    private String _colName;
    private int _precision;
    private int _scale;


    public StructMetadata(String schemaName, @NotNull String fieldTypeName, @NotNull String colName, int precision, int scale) {
      _fieldTypeName = fieldTypeName;
      _schemaName = schemaName;
      _colName = colName;
      _precision = precision;
      _scale = scale;
    }

    public String getSchemaName() {
      return _schemaName;
    }

    public String getFieldTypeName() {
      return _fieldTypeName;
    }

    public String getColName() {
      return _colName;
    }

    public int getPrecision() {
      return _precision;
    }

    public int getScale() {
      return _scale;
    }
  }

  public static class TableMetadata {
    private String _columnSchemaName;
    private String _columnFieldTypeName;
    private String _colName;
    private int _precision;
    private int _scale;

    public TableMetadata(@NotNull String colTypeName, @NotNull String colName, int precision, int scale) {
      String[] columnTypeParts = colTypeName.split("\\.");

      if (columnTypeParts.length == 1) {
        _columnSchemaName = null;
        _columnFieldTypeName = columnTypeParts[0];
      } else {
        _columnSchemaName = columnTypeParts[0];
        _columnFieldTypeName = columnTypeParts[1];
      }

      _precision = precision;
      _scale = scale;
      _colName = colName;
    }

    public String getColumnSchemaName() {
      return _columnSchemaName;
    }

    public String getColumnFieldTypeName() {
      return _columnFieldTypeName;
    }

    public String getColName() {
      return _colName;
    }

    public int getPrecision() {
      return _precision;
    }

    public int getScale() {
      return _scale;
    }
  }
}
