package com.medichain.inv.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * Daily consumption of one medicine at one facility.
 * demanded = what patients needed, issued = what was given. issued &lt; demanded means a stock-out day.
 */
@Entity
public class Consumption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long facilityId;
    public Long medicineId;
    @Column(name = "consumption_date")   // "date" is a reserved word in some databases
    public LocalDate date;
    public int issued;
    public int demanded;
}
