package com.medichain.inv.entity;

import jakarta.persistence.*;

/**
 * A health facility: PHC (Primary Health Centre), CHC (Community Health Centre) or DH (District Hospital).
 * Fields are public to keep the PoC short (JPA and JSON both work with public fields).
 */
@Entity
public class Facility {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String code;        // PHC-GDY
    public String name;        // PHC Gudiyatham
    public String type;        // PHC / CHC / DH
    public String district;    // Vellore
    public double latitude;
    public double longitude;
    public int population;     // people served
}
