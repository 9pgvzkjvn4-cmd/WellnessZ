package com.wellnessz.e06.feed;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

/** Default pull connector: the HRIS pushes absences to {@code /integration/absence/episodes}, so the pull returns nothing. */
@Component
public class NoopAbsenceFeedConnector implements AbsenceFeedConnector {

    @Override
    public String sourceSystem() {
        return "HRIS";
    }

    @Override
    public List<FeedRow> pull(LocalDate since) {
        return List.of();
    }
}
