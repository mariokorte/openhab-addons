package org.openhab.binding.enera.internal.model;

public class EneraAddress {
    private String City;
    private String ZipCode;
    private String Street;

    public void setCity(String city) {
        City = city;
    }

    public void setZipCode(String zipCode) {
        ZipCode = zipCode;
    }

    public void setStreet(String street) {
        Street = street;
    }

    public String getCity() {
        return City;
    }

    public String getZipCode() {
        return ZipCode;
    }

    public String getStreet() {
        return Street;
    }
}
