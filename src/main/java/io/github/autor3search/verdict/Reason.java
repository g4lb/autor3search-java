package io.github.autor3search.verdict;

/** A stable, machine-readable code explaining a {@link Status}. */
public enum Reason {
    IMPROVED("improved"),
    NO_IMPROVEMENT("no_significant_improvement"),
    BELOW_MIN_EFFECT("improvement_below_min_effect"),
    GUARD_REGRESSION("guard_regression"),
    SCOPE("scope_violation"),
    CONFIG_CHANGED("config_changed"),
    NEW_TEST_FILE("new_test_file"),
    MISSING_TEST_FILE("missing_test_file"),
    SYMLINK_SWAP("symlink_swap"),
    FROZEN_TAMPERED("frozen_store_tampered"),
    BASELINE_TAMPERED("baseline_tampered"),
    BUILD("build_failed"),
    MEASUREMENT("measurement_failed"),
    TESTS("tests_failed"),
    TIMEOUT("timeout"),
    STOP_FORCED("stop_forced");

    private final String code;

    Reason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }
}
