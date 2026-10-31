package com.wellnessz.casekernel.device;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;

/**
 * One place for the device modules to obtain the kernel device SPIs, with safe defaults when the implementing module is
 * not deployed: empty catalogue, no capture session, fail-closed endpoint authentication, security events written to the
 * hash-chained platform audit log. Commands that need a specific module (gateway, diagnostics, provisioning, signatures,
 * secrets) answer 503 {@code module_disabled} when it is absent.
 */
@Component
public class DeviceSpis {

    private final ObjectProvider<DeviceCatalogueLookup> catalogue;
    private final ObjectProvider<DeviceEndpointAuthenticator> authenticator;
    private final ObjectProvider<DeviceSecurityEventPublisher> securityEvents;
    private final ObjectProvider<DeviceCaptureSessions> sessions;
    private final ObjectProvider<DeviceObservationGateway> gateway;
    private final ObjectProvider<DeviceDiagnostics> diagnostics;
    private final ObjectProvider<DeviceEndpointProvisioning> provisioning;
    private final ObjectProvider<DevicePackageSignatures> signatures;
    private final ObjectProvider<DeviceSecretStore> secrets;
    private final ObjectProvider<DeviceEquipmentRegister> equipment;
    private final List<DeviceObservationSink> sinks;
    private final List<DeviceObservationListener> observationListeners;
    private final List<DeviceEndpointStatusListener> endpointListeners;
    private final List<DeviceWorklistSource> worklists;
    private final DeviceSecurityEventPublisher auditFallback;

    public DeviceSpis(ObjectProvider<DeviceCatalogueLookup> catalogue, ObjectProvider<DeviceEndpointAuthenticator> authenticator,
                      ObjectProvider<DeviceSecurityEventPublisher> securityEvents, ObjectProvider<DeviceCaptureSessions> sessions,
                      ObjectProvider<DeviceObservationGateway> gateway, ObjectProvider<DeviceDiagnostics> diagnostics,
                      ObjectProvider<DeviceEndpointProvisioning> provisioning, ObjectProvider<DevicePackageSignatures> signatures,
                      ObjectProvider<DeviceSecretStore> secrets, ObjectProvider<DeviceEquipmentRegister> equipment,
                      List<DeviceObservationSink> sinks, List<DeviceObservationListener> observationListeners,
                      List<DeviceEndpointStatusListener> endpointListeners, List<DeviceWorklistSource> worklists, AuditService audit) {
        this.catalogue = catalogue;
        this.authenticator = authenticator;
        this.securityEvents = securityEvents;
        this.sessions = sessions;
        this.gateway = gateway;
        this.diagnostics = diagnostics;
        this.provisioning = provisioning;
        this.signatures = signatures;
        this.secrets = secrets;
        this.equipment = equipment;
        this.sinks = List.copyOf(sinks);
        this.observationListeners = List.copyOf(observationListeners);
        this.endpointListeners = List.copyOf(endpointListeners);
        this.worklists = List.copyOf(worklists);
        this.auditFallback = e -> audit.recordAs(e.actor() == null ? "system" : e.actor(), "DEVICE_SECURITY", "DEVICE_SECURITY_EVENT",
                "DEVICE_ENDPOINT", e.endpointId() == null ? "-" : e.endpointId(),
                e.type().endsWith("_FAILED") ? AuditService.Outcome.DENIED : AuditService.Outcome.SUCCESS,
                e.type() + " " + e.severity() + (e.reasonCode() == null ? "" : " " + e.reasonCode()));
    }

    public DeviceCatalogueLookup catalogue() {
        return catalogue.getIfAvailable(() -> DeviceCatalogueLookup.EMPTY);
    }

    public DeviceEndpointAuthenticator authenticator() {
        return authenticator.getIfAvailable(() -> DeviceEndpointAuthenticator.DENY_ALL);
    }

    public DeviceSecurityEventPublisher securityEvents() {
        return securityEvents.getIfAvailable(() -> auditFallback);
    }

    public DeviceCaptureSessions sessions() {
        return sessions.getIfAvailable(() -> DeviceCaptureSessions.NONE);
    }

    public DeviceEquipmentRegister equipment() {
        return equipment.getIfAvailable(() -> DeviceEquipmentRegister.NONE);
    }

    public DeviceObservationGateway gateway() {
        return required(gateway.getIfAvailable(), "E31");
    }

    public DeviceDiagnostics diagnostics() {
        return required(diagnostics.getIfAvailable(), "E31");
    }

    public DeviceEndpointProvisioning provisioning() {
        return required(provisioning.getIfAvailable(), "E34");
    }

    public DevicePackageSignatures signatures() {
        return required(signatures.getIfAvailable(), "E34");
    }

    public DeviceSecretStore secrets() {
        return required(secrets.getIfAvailable(), "E34");
    }

    /** All routing sinks (E03, E07, E10, E12 adapters). */
    public List<DeviceObservationSink> sinks() {
        return sinks;
    }

    /** The sink owning a routing target, if deployed. */
    public java.util.Optional<DeviceObservationSink> sink(String target) {
        return sinks.stream().filter(s -> s.target().equals(target)).findFirst();
    }

    public List<DeviceObservationListener> observationListeners() {
        return observationListeners;
    }

    public List<DeviceEndpointStatusListener> endpointListeners() {
        return endpointListeners;
    }

    public List<DeviceWorklistSource> worklists() {
        return worklists;
    }

    private static <T> T required(T impl, String module) {
        if (impl == null) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "module_disabled",
                    "Module " + module + " is not deployed");
        }
        return impl;
    }
}
