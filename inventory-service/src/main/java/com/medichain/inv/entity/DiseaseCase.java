package com.medichain.inv.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/** Weekly reported cases of a disease in a district (seasonal pattern, used by the forecast). */
@Entity
public class DiseaseCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String district;
    public String disease;     // FEVER, DIARRHOEA, ARI
    public LocalDate weekStart;
    public int cases;
}
