package com.wellnessz.e10.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface E10PolicyRepository extends JpaRepository<TestingPolicy, String> {

    List<TestingPolicy> findAllByOrderByRoleGroupAsc();
}
