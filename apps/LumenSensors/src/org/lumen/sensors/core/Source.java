package org.lumen.sensors.core;

/**
 * A provider of samples. Every method runs on the sampler thread (one HandlerThread for the whole
 * app); nothing here may touch views. A source registers a sample only once it has read a plausible
 * value, and hides it ({@link Sample#hide}) as soon as a read fails.
 */
public interface Source {
    /** Sampler started: open the nodes, register what can be read. */
    void start(Sampler s);

    /** One refresh tick. Write sample state under {@code s.registry().lock}. Must not block for long. */
    void poll(Sampler s, long now);

    /** Sampler stops: close fds, drop listeners and callbacks. */
    void stop(Sampler s);
}
