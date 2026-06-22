package com.usal.whbackend.domain;

public class Address {

  private String street;
  private String department;
  private String floor;
  private String postalCode;

  public Address() {}

  public String getStreet() { return street; }
  public void setStreet(String street) { this.street = street; }

  public String getDepartment() { return department; }
  public void setDepartment(String department) { this.department = department; }

  public String getFloor() { return floor; }
  public void setFloor(String floor) { this.floor = floor; }

  public String getPostalCode() { return postalCode; }
  public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
}