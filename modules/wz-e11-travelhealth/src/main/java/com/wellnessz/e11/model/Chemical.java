package com.wellnessz.e11.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Chemical register entry with its safety data sheet (E11-F03). */
@Entity(name = "E11Chemical")
@Table(name = "chemical", schema = "travelhealth")
public class Chemical {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "un_number")
    private String unNumber;
    @Column(name = "sds_url")
    private String sdsUrl;
    @Column(name = "hazards")
    private String hazards;

    public String getCode() {
        return code;
    }

    public void setCode(String v) {
        this.code = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getUnNumber() {
        return unNumber;
    }

    public void setUnNumber(String v) {
        this.unNumber = v;
    }

    public String getSdsUrl() {
        return sdsUrl;
    }

    public void setSdsUrl(String v) {
        this.sdsUrl = v;
    }

    public String getHazards() {
        return hazards;
    }

    public void setHazards(String v) {
        this.hazards = v;
    }
}
