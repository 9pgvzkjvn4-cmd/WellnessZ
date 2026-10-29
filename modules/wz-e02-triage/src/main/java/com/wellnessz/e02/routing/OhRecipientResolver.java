package com.wellnessz.e02.routing;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e01.notify.NotificationRule;
import com.wellnessz.e01.notify.RecipientResolver;
import com.wellnessz.e02.model.WorkQueueRepository;
import com.wellnessz.masterdata.StaffMemberRepository;

/** Resolves OH queue distribution lists, case owner and Nursing Head(s) for the E01 notification matrix. */
@Component
public class OhRecipientResolver implements RecipientResolver {

    private final WorkQueueRepository queues;
    private final StaffMemberRepository staff;

    public OhRecipientResolver(WorkQueueRepository queues, StaffMemberRepository staff) {
        this.queues = queues;
        this.staff = staff;
    }

    @Override
    public List<Address> resolve(NotificationRule.Recipient recipient, OhCase c) {
        List<Address> out = new ArrayList<>();
        switch (recipient) {
            case OH_QUEUE -> {
                if (c.getQueueCode() != null) {
                    queues.findById(c.getQueueCode()).filter(q -> q.getEmail() != null)
                            .ifPresent(q -> out.add(new Address("queue:" + q.getCode(), q.getEmail())));
                }
            }
            case CASE_OWNER -> {
                if (c.getOwnerUsername() != null) {
                    staff.findById(c.getOwnerUsername()).filter(s -> s.getEmail() != null)
                            .ifPresent(s -> out.add(new Address(s.getUsername(), s.getEmail())));
                }
            }
            case NURSING_HEAD -> staff.findByActiveTrueOrderByDisplayName().stream()
                    .filter(s -> s.hasRole(Roles.NURSING_HEAD) && s.getEmail() != null)
                    .forEach(s -> out.add(new Address(s.getUsername(), s.getEmail())));
            default -> {
                // other recipients are resolved by E01
            }
        }
        return out;
    }
}
