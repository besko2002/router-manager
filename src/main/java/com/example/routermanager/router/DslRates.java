package com.example.routermanager.router;

/** DSL sync rates in kbit/s — the line capacity, not traffic. Null when the router omits one. */
public record DslRates(
        Integer downCurrentKbps,
        Integer downMaxKbps,
        Integer upCurrentKbps,
        Integer upMaxKbps,
        String status) {

    public static DslRates empty() {
        return new DslRates(null, null, null, null, "");
    }
}
