package com.wellnessz.e03.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e03.orders.OrderService;
import com.wellnessz.e03.results.ResultService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Orders, order sets, LIS gateway queue, results ingestion, device capture, worklist and result letters (E03-F04). */
@RestController
@RequestMapping("/api/v1")
public class OrderResultController {

    static final String ORDERERS = "hasAnyRole('OH_PHYSICIAN','AME','CMO','OH_CLINICIAN')";
    static final String DEVICE = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN','INTEGRATION')";
    static final String CATEGORY = "LAB|IMAGING|AUDIOMETRY|SPIROMETRY|VISION|ECG";

    private final OrderService orders;
    private final ResultService results;
    private final CurrentUserProvider users;

    public OrderResultController(OrderService orders, ResultService results, CurrentUserProvider users) {
        this.orders = orders;
        this.results = results;
        this.users = users;
    }

    // ------------------------------------------------------------------ orders

    @GetMapping("/order-sets")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<OrderService.OrderSetView> orderSets() {
        return orders.orderSets();
    }

    @PostMapping("/employees/{employeeId}/orders")
    @PreAuthorize(ORDERERS)
    @ResponseStatus(HttpStatus.CREATED)
    public OrderService.OrderView place(@PathVariable @Pattern(regexp = ChartController.EMPLOYEE_ID) String employeeId,
                                        @Valid @RequestBody OrderService.OrderRequest r) {
        return orders.place(users.require(), employeeId, r);
    }

    @GetMapping("/employees/{employeeId}/orders")
    public List<OrderService.OrderView> list(@PathVariable @Pattern(regexp = ChartController.EMPLOYEE_ID) String employeeId) {
        return orders.list(users.require(), employeeId);
    }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize(ORDERERS)
    public OrderService.OrderView cancel(@PathVariable UUID id) {
        return orders.cancel(users.require(), id);
    }

    // ------------------------------------------------------------------ lab gateway (INTEGRATION)

    @GetMapping("/integration/lis/orders")
    @PreAuthorize("hasRole('INTEGRATION')")
    public List<OrderService.LisOrder> lisQueue(@RequestParam(defaultValue = "QUEUED") @Pattern(regexp = "QUEUED|SENT") String status) {
        return orders.lisQueue(status);
    }

    @PostMapping("/integration/lis/orders/{id}/ack")
    @PreAuthorize("hasRole('INTEGRATION')")
    public OrderService.LisOrder lisAck(@PathVariable UUID id) {
        return orders.acknowledge(id);
    }

    @PostMapping("/integration/lis/results")
    @PreAuthorize("hasRole('INTEGRATION')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResultService.IngestResult lisResults(@Valid @RequestBody ResultService.LisResults r) {
        return results.ingest(r, users.require().username());
    }

    // ------------------------------------------------------------------ results

    @PostMapping("/employees/{employeeId}/results/device")
    @PreAuthorize(DEVICE)
    @ResponseStatus(HttpStatus.CREATED)
    public ResultService.ResultView device(@PathVariable @Pattern(regexp = ChartController.EMPLOYEE_ID) String employeeId,
                                           @Valid @RequestBody ResultService.DeviceRequest r) {
        return results.device(users.require(), employeeId, r);
    }

    @GetMapping("/employees/{employeeId}/results")
    public List<ResultService.ResultView> results(@PathVariable @Pattern(regexp = ChartController.EMPLOYEE_ID) String employeeId,
                                                  @RequestParam(required = false) @Pattern(regexp = CATEGORY) String category) {
        return results.list(users.require(), employeeId, category);
    }

    @GetMapping("/results/worklist")
    @PreAuthorize(ORDERERS)
    public List<ResultService.ResultView> worklist(@RequestParam(required = false) Boolean overdue) {
        return results.worklist(users.require(), overdue);
    }

    @PostMapping("/results/{id}/acknowledge")
    @PreAuthorize(ORDERERS)
    public ResultService.ResultView acknowledge(@PathVariable UUID id, @Valid @RequestBody ResultService.AckRequest r) {
        return results.acknowledge(users.require(), id, r);
    }

    @PostMapping("/results/{id}/letter")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.DocumentView letter(@PathVariable UUID id, @Valid @RequestBody(required = false) ResultService.LetterRequest r) {
        return results.letter(users.require(), id, r);
    }
}
