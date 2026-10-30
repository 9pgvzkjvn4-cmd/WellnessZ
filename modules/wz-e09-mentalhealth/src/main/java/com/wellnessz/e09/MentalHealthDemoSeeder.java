package com.wellnessz.e09;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.e09.model.E09PeerContactRequestRepository;
import com.wellnessz.e09.model.E09SupportCaseRepository;
import com.wellnessz.e09.model.E09SupportNoteRepository;
import com.wellnessz.e09.model.SupportCase;
import com.wellnessz.e09.model.SupportNote;
import com.wellnessz.e09.support.MhSupport;

/**
 * Local / test profiles only: completes the E09 demo data (db/demo/R__demo_e09_mentalhealth.sql) with the values that are
 * encrypted at field level and therefore cannot be written by SQL – contact phones, the self-referral message, a clinical note
 * and the peer-support callback number. Idempotent; does nothing when the demo rows are not present.
 */
@Component
@Profile({"local", "test"})
public class MentalHealthDemoSeeder {

    static final UUID OFFICE_CASE = UUID.fromString("e09de000-0000-4000-a000-000000000001");
    static final UUID URGENT_CASE = UUID.fromString("e09de000-0000-4000-a000-000000000002");
    static final String PEER_REQUEST = "PS-DEMQ-7K2R";
    private static final Logger log = LoggerFactory.getLogger(MentalHealthDemoSeeder.class);

    private final E09SupportCaseRepository cases;
    private final E09SupportNoteRepository notes;
    private final E09PeerContactRequestRepository requests;
    private final MhSupport support;
    private final TransactionTemplate tx;

    public MentalHealthDemoSeeder(E09SupportCaseRepository cases, E09SupportNoteRepository notes, E09PeerContactRequestRepository requests,
                                  MhSupport support, TransactionTemplate tx) {
        this.cases = cases;
        this.notes = notes;
        this.requests = requests;
        this.support = support;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        try {
            tx.executeWithoutResult(s -> {
                cases.findById(OFFICE_CASE).filter(c -> c.getContactPhone() == null).ifPresent(c -> {
                    c.setContactPhone("+44 7700 900105");
                    c.setMessage("Work has been overwhelming since the restructure and I am not sleeping well. I would like to talk to someone.");
                    note(c, "Telephone assessment: work-related stress after restructure, no risk indicators. Agreed EAP counselling (6 sessions) "
                            + "and review in four weeks.");
                });
                cases.findById(URGENT_CASE).filter(c -> c.getContactPhone() == null).ifPresent(c -> c.setContactPhone("+44 7700 900110"));
                requests.findByContactCode(PEER_REQUEST).filter(r -> r.getCallbackPhone() == null && r.getPhonePurgedAt() == null)
                        .ifPresent(r -> r.setCallbackPhone("+44 7700 900777"));
            });
        } catch (RuntimeException e) {
            log.warn("E09 demo seeding skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void note(SupportCase c, String text) {
        SupportNote n = SupportNote.create();
        n.setSupportCaseId(c.getId());
        n.setText(text);
        n.setAuthor("psych1");
        n.setCreatedAt(support.now().minusSeconds(3 * 86_400L));
        notes.save(n);
    }
}
