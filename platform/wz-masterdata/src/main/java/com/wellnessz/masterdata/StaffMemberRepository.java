package com.wellnessz.masterdata;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffMemberRepository extends JpaRepository<StaffMember, String> {

    List<StaffMember> findByActiveTrueOrderByDisplayName();
}
