package com.medichain.inv.entity;

import jakarta.persistence.*;

/** One item of the essential medicine list (names from WHO EML / India NLEM 2022). */
@Entity
public class Medicine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String code;        // PCM500
    public String name;        // Paracetamol
    public String strength;    // 500 mg
    public String unit;        // tablet / sachet / capsule
    public String diseaseTag;  // disease that drives demand: FEVER, DIARRHOEA, ARI or NONE
    public int leadTimeDays;   // days for a new order to arrive
    public double unitPrice;   // rupees per unit
    public String source;      // public list it comes from
}
