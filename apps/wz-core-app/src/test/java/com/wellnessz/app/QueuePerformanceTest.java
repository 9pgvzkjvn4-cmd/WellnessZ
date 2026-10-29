package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** E02-BL-010: queue response &lt; 2 s with 5 000 open cases. Seeds data directly with SQL (as migrator-free DML). */
class QueuePerformanceTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void teamQueueAndCountsRespondWithinTwoSecondsAt5000OpenCases() throws Exception {
        jdbc.update("""
                insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code,
                       category, case_type, source, status, classification, fitness_status, urgency, queue_code, owner_username,
                       created_at, updated_at)
                select gen_random_uuid(), 'PERF-' || g, 'P' || g, 'Perf ' || g, 'GROUND', '/AIRLINE/OPS/GROUND', 'GND',
                       'INJURY', 'INJURY', 'EHS_INCIDENT', 'TRIAGE', 'CLINICAL', 'NOT_ASSESSED',
                       (array['URGENT','ROUTINE','SEMI_URGENT'])[1 + g % 3], 'OH_GENERAL',
                       case when g % 4 = 0 then null else (array['nurse1','nurse2','clinician1'])[1 + g % 3] end,
                       now() - (g || ' minutes')::interval, now()
                  from generate_series(1, 5000) g
                on conflict (case_ref) do nothing""");
        jdbc.update("""
                insert into triage.sla_timer (id, case_id, milestone, started_at, due_at, amber_at, status)
                select gen_random_uuid(), c.id, 'TRIAGE', c.created_at, c.created_at + interval '8 hours', c.created_at + interval '6 hours', 'RUNNING'
                  from casemgmt.oh_case c where c.case_ref like 'PERF-%'
                   and not exists (select 1 from triage.sla_timer t where t.case_id = c.id)""");
        jdbc.execute("analyze");
        var head = user("nursinghead1", "NH1", null, "NURSING_HEAD", "OH_CLINICIAN");
        mvc.perform(get("/api/v1/queues/TEAM").param("size", "50").with(head)).andExpect(status().isOk()); // warm-up
        long t0 = System.nanoTime();
        mvc.perform(get("/api/v1/queues/TEAM").param("size", "50").with(head)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/queues/UNASSIGNED").param("size", "50").with(head)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/queues/counts").with(head)).andExpect(status().isOk());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("3 queue calls at 5 000 open cases").isLessThan(2000);
    }
}
