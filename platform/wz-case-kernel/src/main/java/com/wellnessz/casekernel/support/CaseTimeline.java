package com.wellnessz.casekernel.support;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseEvent;
import com.wellnessz.casekernel.model.CaseEventRepository;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Writes non-clinical timeline entries for a case; used by the kernel and by every module. */
@Component
public class CaseTimeline {

    private final CaseEventRepository events;
    private final CurrentUserProvider users;
    private final Clock clock;

    public CaseTimeline(CaseEventRepository events, CurrentUserProvider users, Clock clock) {
        this.events = events;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID caseId, String type, String summary) {
        events.save(new CaseEvent(caseId, type, summary, users.actorOrSystem(), clock.instant()));
    }

    public List<CaseEvent> of(UUID caseId) {
        return events.findByCaseIdOrderByOccurredAtAsc(caseId);
    }
}
