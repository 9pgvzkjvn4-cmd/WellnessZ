package com.wellnessz.masterdata;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, String> {

    Optional<Employee> findByBadgeIdAndActiveTrue(String badgeId);

    /** Name / id search, optionally restricted to departments whose org path lies inside {@code scopePath}. */
    @Query("""
            select e from Employee e where e.active = true
               and (lower(e.displayName) like lower(concat('%', ?1, '%')) or e.employeeId = ?1)
               and (?2 is null or e.departmentCode in
                    (select o.code from OrgUnit o where o.path = ?2 or o.path like concat(?2, '/%')))
             order by e.displayName""")
    List<Employee> search(String q, String scopePath, Pageable pageable);
}
