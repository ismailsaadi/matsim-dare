# clean_rail_gtfs.R
# -----------------------------------------------------------------------------
# Clean the Greater-Manchester rail GTFS (gtfs_rail_gm.zip, produced by
# convert_rail_gtfs.R) ahead of merging it with the TfGM tram/bus feed.
#
# Driven by the MobilityData validator report. This script:
#   1. drops fully-expired calendar services and their trips
#      -> clears the `expired_calendar` warnings;
#   2. monotonic-clamps stop_times within each trip so times never run
#      backwards (arrival <= departure at a stop, and each arrival >= the
#      previous departure) -> clears every `start_and_end_range_out_of_order`
#      (602) and `stop_time_with_arrival_before_previous_departure_time` (32)
#      ERROR. Root cause: atoc2gtfs converting ATOC half-minute timings leaves
#      30-second inconsistencies; the clamp distorts times by <= 30 s;
#   3. drops the non-standard `train_category` / `power_type` columns
#      -> clears `unknown_column`;
#   4. optionally drops trips that still contain an impossibly fast segment
#      after clamping (genuinely corrupt timing) -- see DROP_FAST_TRIPS.
#
# Cosmetic warnings left untouched: residual fast_travel_* (mostly far
# segments of long cross-country trains kept whole by the GM clip),
# missing_bike_allowance, duplicate_route_name, mixed_case_recommended_field,
# missing_recommended_file.
#
# DISK SPACE: write_gtfs() stages the feed as uncompressed .txt in R's temp
# directory before zipping. If C: is short on space, point TMPDIR at a roomier
# drive before starting R (see clean_tfgm_gtfs.R header), then restart R.
#
# Run from the repository root:
#   Rscript src/main/java/en/phm/pt/clean_rail_gtfs.R
# -----------------------------------------------------------------------------

## ---- configuration ----------------------------------------------------------
INPUT_ZIP  <- "D:/Downloads/gtfs_rail_gm.zip"
OUTPUT_ZIP <- "D:/Downloads/gtfs_rail_gm_clean.zip"

# Drop trips that still have a segment faster than MAX_SPEED_KPH after the
# stop-time clamp (genuinely corrupt timing). Off by default -- the 634 ERRORS
# are fixed by the clamp regardless; this only trims residual `fast_travel`
# WARNINGS, at the cost of removing whole trips.
DROP_FAST_TRIPS <- FALSE
MAX_SPEED_KPH   <- 400          # GB rail tops out well below this

# Non-standard columns to drop, as table = column.
DROP_COLUMNS <- list(routes = "train_category", trips = "power_type")
## ----------------------------------------------------------------------------


# ---- dependencies -----------------------------------------------------------
CRAN <- "https://cloud.r-project.org"
for (pkg in c("gtfstools", "data.table")) {
  if (!requireNamespace(pkg, quietly = TRUE)) {
    message("Installing ", pkg, " ...")
    install.packages(pkg, repos = CRAN)
  }
}
library(gtfstools)


# ---- helpers ----------------------------------------------------------------
# GTFS dates are YYYYMMDD; gtfstools may return Date / integer / character.
parse_gtfs_date <- function(x) {
  if (inherits(x, "Date")) return(x)
  as.Date(as.character(x), format = "%Y%m%d")
}

# "HH:MM:SS" (hours may exceed 24) <-> seconds after midnight.
parse_hms <- function(x) {
  if (is.numeric(x)) return(as.numeric(x))
  x <- as.character(x)
  h <- suppressWarnings(as.numeric(sub("^(\\d+):.*$",       "\\1", x)))
  m <- suppressWarnings(as.numeric(sub("^\\d+:(\\d+):.*$",  "\\1", x)))
  s <- suppressWarnings(as.numeric(sub("^\\d+:\\d+:(\\d+)$", "\\1", x)))
  h * 3600 + m * 60 + s
}
format_hms <- function(s) {
  out <- rep("", length(s))
  ok  <- !is.na(s)
  ss  <- round(s[ok])
  out[ok] <- sprintf("%02d:%02d:%02d",
                     as.integer(ss %/% 3600L),
                     as.integer((ss %% 3600L) %/% 60L),
                     as.integer(ss %% 60L))
  out
}

# Clamp one trip's interleaved [arr1, dep1, arr2, dep2, ...] to be
# non-decreasing. Trips with any missing time are left untouched.
clamp_trip <- function(arr_s, dep_s) {
  if (anyNA(arr_s) || anyNA(dep_s)) return(list(arr_s, dep_s))
  n <- length(arr_s)
  v <- numeric(2L * n)
  v[seq.int(1L, 2L * n, by = 2L)] <- arr_s
  v[seq.int(2L, 2L * n, by = 2L)] <- dep_s
  v <- cummax(v)
  list(v[seq.int(1L, 2L * n, by = 2L)], v[seq.int(2L, 2L * n, by = 2L)])
}

haversine_km <- function(lon1, lat1, lon2, lat2) {
  r <- pi / 180
  a <- sin((lat2 - lat1) * r / 2)^2 +
       cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2)^2
  2 * 6371 * asin(pmin(1, sqrt(a)))
}

feed_counts <- function(g) {
  c(routes     = nrow(g$routes),
    trips      = nrow(g$trips),
    stops      = nrow(g$stops),
    services   = if (is.null(g$calendar)) 0L else nrow(g$calendar),
    stop_times = nrow(g$stop_times))
}


# ---- 0. read ----------------------------------------------------------------
if (!file.exists(INPUT_ZIP)) stop("Input feed not found: ", INPUT_ZIP, call. = FALSE)
message("Reading ", INPUT_ZIP, " ...")
gtfs   <- read_gtfs(INPUT_ZIP)
before <- feed_counts(gtfs)


# ---- 1. drop fully-expired calendar services --------------------------------
if (!is.null(gtfs$calendar) && nrow(gtfs$calendar) > 0) {
  end_dt  <- parse_gtfs_date(gtfs$calendar$end_date)
  expired <- gtfs$calendar$service_id[!is.na(end_dt) & end_dt < Sys.Date()]
  if (length(expired) > 0) {
    message("Dropping ", length(expired),
            " expired service(s) and their trips ...")
    gtfs <- filter_by_service_id(gtfs, expired, keep = FALSE)
  } else {
    message("No expired calendar services found.")
  }
}


# ---- 2. monotonic stop-time clamp -------------------------------------------
message("Clamping stop times per trip (this can take a minute) ...")
st <- gtfs$stop_times
st[, stop_sequence := as.integer(stop_sequence)]
data.table::setorder(st, trip_id, stop_sequence)

arr_s0 <- parse_hms(st$arrival_time)
dep_s0 <- parse_hms(st$departure_time)

tmp <- data.table::data.table(trip_id = st$trip_id,
                              arr_s = arr_s0, dep_s = dep_s0)
tmp[, c("arr_s", "dep_s") := clamp_trip(arr_s, dep_s), by = trip_id]

st[, arrival_time   := format_hms(tmp$arr_s)]
st[, departure_time := format_hms(tmp$dep_s)]

n_fixed <- sum(tmp$arr_s != arr_s0 | tmp$dep_s != dep_s0, na.rm = TRUE)
message("  ", n_fixed, " stop-time row(s) adjusted (<= 30 s each).")


# ---- 3. (optional) drop trips with corrupt residual speeds ------------------
n_fast_trips <- 0L
if (DROP_FAST_TRIPS) {
  message("Checking for trips with segments > ", MAX_SPEED_KPH, " km/h ...")
  sp <- merge(
    gtfs$stop_times[, .(trip_id, stop_id, stop_sequence,
                        arrival_time, departure_time)],
    gtfs$stops[, .(stop_id, stop_lon, stop_lat)],
    by = "stop_id", all.x = TRUE
  )
  data.table::setorder(sp, trip_id, stop_sequence)
  sp[, `:=`(arr_s = parse_hms(arrival_time), dep_s = parse_hms(departure_time))]
  sp[, `:=`(p_lon = data.table::shift(stop_lon),
            p_lat = data.table::shift(stop_lat),
            p_dep = data.table::shift(dep_s)), by = trip_id]
  sp[, seg_km  := haversine_km(p_lon, p_lat, stop_lon, stop_lat)]
  sp[, seg_kph := data.table::fifelse((arr_s - p_dep) > 0,
                                      seg_km / ((arr_s - p_dep) / 3600),
                                      Inf)]
  fast <- unique(sp[!is.na(seg_km) & seg_km > 0.1 & seg_kph > MAX_SPEED_KPH,
                    trip_id])
  if (length(fast) > 0) {
    n_fast_trips <- length(fast)
    message("  dropping ", n_fast_trips, " trip(s) with corrupt timing ...")
    gtfs <- filter_by_trip_id(gtfs, fast, keep = FALSE)
  } else {
    message("  none found.")
  }
}


# ---- 4. drop non-standard columns -------------------------------------------
for (tbl in names(DROP_COLUMNS)) {
  col <- DROP_COLUMNS[[tbl]]
  if (!is.null(gtfs[[tbl]]) && col %in% names(gtfs[[tbl]])) {
    data.table::set(gtfs[[tbl]], j = col, value = NULL)
    message("Removed non-standard column ", tbl, ".", col, ".")
  }
}


# ---- 5. write ---------------------------------------------------------------
after <- feed_counts(gtfs)
message("Writing cleaned rail GTFS to ", OUTPUT_ZIP, " ...")
write_gtfs(gtfs, OUTPUT_ZIP)

cat("\n", strrep("=", 60), "\n", sep = "")
cat("RAIL GTFS CLEANING SUMMARY\n")
cat(strrep("=", 60), "\n", sep = "")
for (k in names(before)) {
  cat(sprintf("  %-11s %11s  ->  %11s\n", k, before[[k]], after[[k]]))
}
cat(sprintf("\n  stop-time rows clamped : %d\n", n_fixed))
cat(sprintf("  fast trips dropped     : %d\n", n_fast_trips))
cat("\n  Output: ", OUTPUT_ZIP, "\n", sep = "")
cat("  Re-validate by adding it to FEEDS in validate_gtfs_feeds.R\n")
