package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * customTime rules shared by the JSON and gRPC paths. GCS stores the value as a protobuf
 * Timestamp, renders it in UTC with 0, 3, 6 or 9 fraction digits, never removes it once set,
 * and refuses to move it backwards.
 */
final class GcsCustomTime {

    private static final String PARSE_ERROR = """
            Parse Error: Invalid value for type.googleapis.com/google.protobuf.Timestamp field: \
            'Field 'customTime', Illegal timestamp format; timestamps must end with 'Z' or have \
            a valid timezone offset.'.""";

    private static final DateTimeFormatter ERROR_SECONDS = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private GcsCustomTime() {
    }

    static String normalize(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw GcpException.invalidArgument(PARSE_ERROR);
        }
    }

    static void requireNotDecreased(String previous, String next) {
        if (previous == null) {
            return;
        }
        var before = Instant.parse(previous);
        var after = Instant.parse(next);
        if (after.isBefore(before)) {
            throw GcpException.invalidArgument("Custom time cannot be decreased. Previously: "
                    + errorFormat(before) + ". Attempting to set: " + errorFormat(after) + ".");
        }
    }

    // The message renders the fraction without trailing zeros and with an explicit +00:00.
    private static String errorFormat(Instant instant) {
        var text = new StringBuilder(ERROR_SECONDS.format(instant));
        if (instant.getNano() != 0) {
            text.append('.').append(String.format("%09d", instant.getNano()).replaceFirst("0+$", ""));
        }
        return text.append("+00:00").toString();
    }
}
