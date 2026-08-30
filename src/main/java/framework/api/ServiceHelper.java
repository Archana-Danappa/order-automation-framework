package framework.api;

/**
 * Standard lifecycle contract implemented by every business-operation helper
 * in the framework (CreateOrderHelper, ProcessPaymentHelper,
 * ReserveInventoryHelper, OrderLifecycleHelper, ...).
 *
 * init()      - build headers, payload, and any pre-conditions.
 * process()   - make the actual API call.
 * validate()  - assert the response and, where relevant, downstream state.
 * test()      - orchestrates the three steps and returns `this` so it can be
 *               chained straight out of a builder from within a @Test method:
 *
 *                   CreateOrderHelper.builder()
 *                       .orderContext(ctx)
 *                       .build()
 *                       .test();
 *
 * Every helper overriding these methods against the same interface reference
 * is what gives the framework runtime polymorphism - callers only ever
 * program against ServiceHelper, never against a concrete helper class.
 */
public interface ServiceHelper {

    ServiceHelper init();

    ServiceHelper process();

    ServiceHelper validate();

    default ServiceHelper test() {
        return this.init().process().validate();
    }
}
