/**
 * Kernel SPIs of the medical device integration engine (increment 6, E31–E34). The four device modules – E31 engine
 * ({@code com.wellnessz.e31}), E32 console ({@code com.wellnessz.e32}), E33 capture ({@code com.wellnessz.e33}) and E34
 * endpoint security ({@code com.wellnessz.e34}) – never depend on each other or on the OH business modules; every
 * interaction goes through the interfaces in this package (binding contract: {@code docs/increment-06/API-Contract-E31-E34.md}).
 *
 * <p>Optional SPIs are resolved through {@link com.wellnessz.casekernel.device.DeviceSpis}, which supplies a safe default
 * when the implementing module is not deployed (empty catalogue, no capture session, deny-all endpoint authentication,
 * audit-log security events). Multi-implementation SPIs ({@link com.wellnessz.casekernel.device.DeviceObservationSink},
 * {@link com.wellnessz.casekernel.device.DeviceObservationListener},
 * {@link com.wellnessz.casekernel.device.DeviceEndpointStatusListener}) are injected as lists.</p>
 *
 * <p>Nothing in these records may carry free-text clinical content outside {@link com.wellnessz.casekernel.device.DeviceObservation};
 * security events, traces and health figures are PHI-free by contract.</p>
 */
package com.wellnessz.casekernel.device;
