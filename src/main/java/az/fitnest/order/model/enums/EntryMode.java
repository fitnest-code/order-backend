package az.fitnest.order.model.enums;

/**
 * Entry mode for a subscription – reflects the home‑gym state.
 *
 * <p>Values introduced in version 2 of the spec:
 * <ul>
 *   <li>{@code HOME_GYM_PENDING} – no home gym selected yet.</li>
 *   <li>{@code HOME_GYM} – active unlimited‑access gym.</li>
 *   <li>{@code STANDARD} – home gym removed / expired.</li>
 * </ul>
 */
public enum EntryMode {
    HOME_GYM_PENDING,
    HOME_GYM,
    STANDARD
}
