package com.wellnessz.e11.web;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e11.travel.TravelService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E11-F01 travel & posting consultation: destination content, consultations, recommendations and the employee's travel view. */
@RestController("e11TravelController")
@RequestMapping("/api/v1")
public class TravelController {

    private final TravelService travel;
    private final CurrentUserProvider users;

    public TravelController(TravelService travel, CurrentUserProvider users) {
        this.travel = travel;
        this.users = users;
    }

    @GetMapping("/travel/destinations")
    @PreAuthorize(Spel.DESTINATION_READ)
    public List<TravelService.DestinationView> destinations() {
        return travel.destinations(users.require());
    }

    @GetMapping("/travel/destinations/{countryCode}")
    @PreAuthorize(Spel.DESTINATION_READ)
    public TravelService.DestinationView destination(@PathVariable @Pattern(regexp = Spel.COUNTRY) String countryCode) {
        return travel.destination(users.require(), countryCode);
    }

    @PutMapping("/travel/destinations/{countryCode}")
    @PreAuthorize(Spel.DESTINATION_WRITE)
    public TravelService.DestinationView putDestination(@PathVariable @Pattern(regexp = Spel.COUNTRY) String countryCode,
                                                        @Valid @RequestBody TravelService.DestinationCommand cmd) {
        return travel.putDestination(users.require(), countryCode, cmd);
    }

    @PostMapping("/travel/consultations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public TravelService.ConsultationView consult(@Valid @RequestBody TravelService.ConsultationCommand cmd) {
        return travel.consult(users.require(), cmd);
    }

    @GetMapping("/travel/consultations")
    @PreAuthorize(Spel.CLINICIANS)
    public List<TravelService.ConsultationView> consultations(@RequestParam(required = false) @Pattern(regexp = Spel.EMPLOYEE_ID) String employeeId) {
        return travel.consultations(users.require(), employeeId);
    }

    @GetMapping("/travel/recommendations")
    @PreAuthorize(Spel.CLINICIANS)
    public TravelService.RecommendationsView recommendations(@RequestParam @Pattern(regexp = Spel.EMPLOYEE_ID) String employeeId,
                                                             @RequestParam @Pattern(regexp = "[A-Z]{2}(,[A-Z]{2}){0,9}") String destinations,
                                                             @RequestParam(required = false) LocalDate departDate,
                                                             @RequestParam(required = false) LocalDate returnDate) {
        return travel.recommendations(users.require(), employeeId, Arrays.asList(destinations.split(",")), departDate, returnDate);
    }

    @GetMapping("/me/travel")
    @PreAuthorize(Spel.EMPLOYEE)
    public TravelService.MyTravel mine() {
        return travel.mine(employee(users.require()));
    }

    static CurrentUser employee(CurrentUser user) {
        if (user.employeeId() == null) {
            throw ApiException.forbidden("No employee number on the session");
        }
        return user;
    }
}
