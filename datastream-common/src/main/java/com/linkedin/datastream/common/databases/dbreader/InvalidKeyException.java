package com.linkedin.datastream.common.databases.dbreader;

/**
 * Exception to indicate the table has no valid keys the seeder can use. This can be used by applications to
 * to skip over tables that cannot be seeded if required.
 */
public class InvalidKeyException extends Exception {

  private static final long serialVersionUID = 2132150605367432532L;

  public InvalidKeyException() {
    super();
  }

  public InvalidKeyException(String message) {
    super(message);
  }
}