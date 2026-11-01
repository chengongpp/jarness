package ch.predmet.jarness.api.kernel;

/** How an agent came into existence. */
public enum Origin {

    /** Created from a template with an empty context: {@code exec(template)}. */
    FRESH,
    /** Created from a copy of the parent's image: {@code fork(image)}. */
    FORK
}
