package net.kencochrane.raven.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.google.common.base.Splitter;
import net.kencochrane.raven.Raven;
import net.kencochrane.raven.RavenFactory;
import net.kencochrane.raven.dsn.Dsn;
import net.kencochrane.raven.dsn.InvalidDsnException;
import net.kencochrane.raven.environment.RavenEnvironment;
import net.kencochrane.raven.event.Event;
import net.kencochrane.raven.event.EventBuilder;
import net.kencochrane.raven.event.interfaces.ExceptionInterface;
import net.kencochrane.raven.event.interfaces.MessageInterface;
import net.kencochrane.raven.event.interfaces.SentryException;
import net.kencochrane.raven.event.interfaces.StackTraceInterface;

import java.util.*;

/**
 * Appender for logback in charge of sending the logged events to a Sentry server.
 */
public class SentryAppender extends AppenderBase<ILoggingEvent> {

    /**
     * Name of the {@link Event#extra} property containing Maker details.
     */
    public static final String LOGBACK_MARKER = "logback-Marker";
    /**
     * Name of the {@link Event#extra} property containing the Thread name.
     */
    public static final String THREAD_NAME = "Raven-Threadname";
    /**
     * Current instance of {@link Raven}.
     *
     * @see #initRaven()
     */
    protected Raven raven;
    /**
     * DSN property of the appender.
     * <p>
     * Might be null in which case the DSN should be detected automatically.
     */
    protected String dsn;
    /**
     * Name of the {@link RavenFactory} being used.
     * <p>
     * Might be null in which case the factory should be defined automatically.
     */
    protected String ravenFactory;
    /**
     * Identifies the version of the application.
     * <p>
     * Might be null in which case the release information will not be sent with the event.
     */
    protected String release;
    /**
     * If set, only events with level = minLevel and up will be recorded.
     */
    protected Level minLevel = Level.WARN;
    /**
     * Additional tags to be sent to sentry.
     * <p>
     * Might be empty in which case no tags are sent.
     */
    protected Map<String, String> tags = Collections.emptyMap();
    /**
     * Extras to use as tags.
     */
    protected Set<String> extraTags = Collections.emptySet();

    /**
     * Creates an instance of SentryAppender.
     */
    public SentryAppender() {
    }

    /**
     * Creates an instance of SentryAppender.
     *
     * @param raven instance of Raven to use with this appender.
     */
    public SentryAppender(Raven raven) {
        this.raven = raven;
    }

    /**
     * Extracts message parameters into a List of Strings.
     * <p>
     * null parameters are kept as null.
     *
     * @param parameters parameters provided to the logging system.
     * @return the parameters formatted as Strings in a List.
     */
    protected static List<String> formatMessageParameters(Object[] parameters) {
        List<String> arguments = new ArrayList<>(parameters.length);
        for (Object argument : parameters) {
            arguments.add((argument != null) ? argument.toString() : null);
        }
        return arguments;
    }

    /**
     * Transforms a {@link Level} into an {@link Event.Level}.
     *
     * @param level original level as defined in logback.
     * @return log level used within raven.
     */
    protected static Event.Level formatLevel(Level level) {
        if (level.isGreaterOrEqual(Level.ERROR)) {
            return Event.Level.ERROR;
        } else if (level.isGreaterOrEqual(Level.WARN)) {
            return Event.Level.WARNING;
        } else if (level.isGreaterOrEqual(Level.INFO)) {
            return Event.Level.INFO;
        } else if (level.isGreaterOrEqual(Level.ALL)) {
            return Event.Level.DEBUG;
        } else return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The raven instance is started in this method instead of {@link #start()} in order to avoid substitute loggers
     * being generated during the instantiation of {@link Raven}.<br>
     * More on <a href="http://www.slf4j.org/codes.html#substituteLogger">www.slf4j.org/codes.html#substituteLogger</a>
     */
    @Override
    protected void append(ILoggingEvent iLoggingEvent) {
        // Do not log the event if the current thread is managed by raven
        if (RavenEnvironment.isManagingThread())
            return;

        RavenEnvironment.startManagingThread();
        try {
            if (raven == null)
                initRaven();

            if (minLevel != null && !iLoggingEvent.getLevel().isGreaterOrEqual(minLevel))
                return;

            Event event = buildEvent(iLoggingEvent);
            raven.sendEvent(event);
        } catch (Exception e) {
            addError("An exception occurred while creating a new event in Raven", e);
        } finally {
            RavenEnvironment.stopManagingThread();
        }
    }

    /**
     * Initialises the Raven instance.
     */
    protected void initRaven() {
        try {
            if (dsn == null)
                dsn = Dsn.dsnLookup();

            raven = RavenFactory.ravenInstance(new Dsn(dsn), ravenFactory);
        } catch (InvalidDsnException e) {
            addError("An exception occurred during the retrieval of the DSN for Raven", e);
        } catch (Exception e) {
            addError("An exception occurred during the creation of a Raven instance", e);
        }
    }

    /**
     * Builds an Event based on the logging event.
     *
     * @param iLoggingEvent Log generated.
     * @return Event containing details provided by the logging system.
     */
    protected Event buildEvent(ILoggingEvent iLoggingEvent) {
        EventBuilder eventBuilder = new EventBuilder()
                .withTimestamp(new Date(iLoggingEvent.getTimeStamp()))
                .withMessage(iLoggingEvent.getFormattedMessage())
                .withLogger(iLoggingEvent.getLoggerName())
                .withLevel(formatLevel(iLoggingEvent.getLevel()))
                .withExtra(THREAD_NAME, iLoggingEvent.getThreadName());

        if (this.release != null) {
            eventBuilder.withRelease(this.release);
        }

        if (iLoggingEvent.getArgumentArray() != null) {
            eventBuilder.withSentryInterface(new MessageInterface(iLoggingEvent.getMessage(),
                    formatMessageParameters(iLoggingEvent.getArgumentArray())));
        }

        if (iLoggingEvent.getThrowableProxy() != null) {
            eventBuilder.withSentryInterface(new ExceptionInterface(extractExceptionQueue(iLoggingEvent)));
        } else if (iLoggingEvent.getCallerData().length > 0) {
            eventBuilder.withSentryInterface(new StackTraceInterface(iLoggingEvent.getCallerData()));
        }

        if (iLoggingEvent.getCallerData().length > 0) {
            eventBuilder.withCulprit(iLoggingEvent.getCallerData()[0]);
        } else {
            eventBuilder.withCulprit(iLoggingEvent.getLoggerName());
        }

        for (Map.Entry<String, String> contextEntry : iLoggingEvent.getLoggerContextVO().getPropertyMap().entrySet()) {
            eventBuilder.withExtra(contextEntry.getKey(), contextEntry.getValue());
        }

        for (Map.Entry<String, String> mdcEntry : iLoggingEvent.getMDCPropertyMap().entrySet()) {
            if (extraTags.contains(mdcEntry.getKey())) {
                eventBuilder.withTag(mdcEntry.getKey(), mdcEntry.getValue());
            } else {
                eventBuilder.withExtra(mdcEntry.getKey(), mdcEntry.getValue());
            }
        }

        if (iLoggingEvent.getMarker() != null)
            eventBuilder.withTag(LOGBACK_MARKER, iLoggingEvent.getMarker().getName());

        for (Map.Entry<String, String> tagEntry : tags.entrySet())
            eventBuilder.withTag(tagEntry.getKey(), tagEntry.getValue());

        raven.runBuilderHelpers(eventBuilder);
        return eventBuilder.build();
    }

    private Deque<SentryException> extractExceptionQueue(ILoggingEvent iLoggingEvent) {
        IThrowableProxy throwableProxy = iLoggingEvent.getThrowableProxy();
        Deque<SentryException> exceptions = new ArrayDeque<>();
        Set<IThrowableProxy> circularityDetector = new HashSet<>();
        StackTraceElement[] enclosingStackTrace = new StackTraceElement[0];

        //Stack the exceptions to send them in the reverse order
        while (throwableProxy != null) {
            if (!circularityDetector.add(throwableProxy)) {
                addWarn("Exiting a circular exception!");
                break;
            }

            StackTraceElement[] stackTraceElements = toStackTraceElements(throwableProxy);
            StackTraceInterface stackTrace = new StackTraceInterface(stackTraceElements, enclosingStackTrace);
            exceptions.push(createSentryExceptionFrom(throwableProxy, stackTrace));
            enclosingStackTrace = stackTraceElements;
            throwableProxy = throwableProxy.getCause();
        }

        return exceptions;
    }

    private SentryException createSentryExceptionFrom(IThrowableProxy throwableProxy, StackTraceInterface stackTrace) {
        String exceptionMessage = throwableProxy.getMessage();
        String[] packageNameSimpleName = extractPackageSimpleClassName(throwableProxy.getClassName());
        String exceptionPackageName = packageNameSimpleName[0];
        String exceptionClassName = packageNameSimpleName[1];

        return new SentryException(exceptionMessage, exceptionClassName, exceptionPackageName, stackTrace);
    }

    private String[] extractPackageSimpleClassName(String canonicalClassName) {
        String[] packageNameSimpleName = new String[2];
        try {
            Class<?> exceptionClass = Class.forName(canonicalClassName);
            Package exceptionPackage = exceptionClass.getPackage();
            packageNameSimpleName[0] = exceptionPackage != null ? exceptionPackage.getName()
                    : SentryException.DEFAULT_PACKAGE_NAME;
            packageNameSimpleName[1] = exceptionClass.getSimpleName();
        } catch (ClassNotFoundException e) {
            int lastDot = canonicalClassName.lastIndexOf('.');
            if (lastDot != -1) {
                packageNameSimpleName[0] = canonicalClassName.substring(0, lastDot);
                packageNameSimpleName[1] = canonicalClassName.substring(lastDot);
            } else {
                packageNameSimpleName[0] = SentryException.DEFAULT_PACKAGE_NAME;
                packageNameSimpleName[1] = canonicalClassName;
            }
        }
        return packageNameSimpleName;
    }

    private StackTraceElement[] toStackTraceElements(IThrowableProxy throwableProxy) {
        StackTraceElementProxy[] stackTraceElementProxies = throwableProxy.getStackTraceElementProxyArray();
        StackTraceElement[] stackTraceElements = new StackTraceElement[stackTraceElementProxies.length];

        for (int i = 0, stackTraceElementsLength = stackTraceElementProxies.length; i < stackTraceElementsLength; i++) {
            stackTraceElements[i] = stackTraceElementProxies[i].getStackTraceElement();
        }

        return stackTraceElements;
    }

    public void setDsn(String dsn) {
        this.dsn = dsn;
    }

    public void setRavenFactory(String ravenFactory) {
        this.ravenFactory = ravenFactory;
    }

    public void setRelease(String release) {
        this.release = release;
    }

    public void setMinLevel(String minLevel) {
        this.minLevel = Level.toLevel(minLevel, Level.WARN);
    }

    /**
     * Set the tags that should be sent along with the events.
     *
     * @param tags A String of tags. key/values are separated by colon(:) and tags are separated by commas(,).
     */
    public void setTags(String tags) {
        this.tags = Splitter.on(",").withKeyValueSeparator(":").split(tags);
    }

    /**
     * Set the mapped extras that will be used to search MDC and upgrade key pair to a tag sent along with the events.
     *
     * @param extraTags A String of extraTags. extraTags are separated by commas(,).
     */
    public void setExtraTags(String extraTags) {
        this.extraTags = new HashSet<>(Arrays.asList(extraTags.split(",")));
    }

    @Override
    public void stop() {
        RavenEnvironment.startManagingThread();
        try {
            if (!isStarted())
                return;
            super.stop();
            if (raven != null)
                raven.closeConnection();
        } catch (Exception e) {
            addError("An exception occurred while closing the Raven connection", e);
        } finally {
            RavenEnvironment.stopManagingThread();
        }
    }
}
