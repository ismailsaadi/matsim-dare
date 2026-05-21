# merge_gtfs_feeds.R
# -----------------------------------------------------------------------------
# Merge the cleaned tram/bus and rail GTFS feeds into a single GTFS for the
# matsim-dare PT pipeline (consumed by PT2MATSimExample).
#
# Inputs are the cleaned, validated feeds:
#   - TfGMgtfsnew_clean.zip   tram + bus  (clean_tfgm_gtfs.R)
#   - gtfs_rail_gm_clean.zip  rail        (convert_rail_gtfs.R + clean_rail_gtfs.R)
#
# The two feeds use independent ID spaces (both have e.g. route_id "101",
# service_id "611"), so gtfstools::merge_gtfs(prefix = ...) is used: it
# prefixes every *_id column in every table per feed -- e.g. "tfgm_101",
# "rail_101" -- so primary keys and foreign-key references stay consistent.
#
# route_type values are NOT touched: both feeds already validate clean with the
# correct modes (0 = tram, 2 = rail, 3 = bus), which is what pt2matsim maps to
# the MATSim transit modes tram / rail / bus.
#
# DISK SPACE: write_gtfs() stages the merged feed as uncompressed .txt in R's
# temp directory before zipping, and the merge holds both feeds in memory. If
# C: is short on space, point TMPDIR at a roomier drive before starting R, then
# restart R.
#
# Run from the repository root:
#   Rscript src/main/java/en/phm/pt/merge_gtfs_feeds.R
# -----------------------------------------------------------------------------

## ---- configuration ----------------------------------------------------------
# Input feeds: <prefix> = <path>. The list name becomes that feed's ID prefix.
FEEDS <- list(
  tfgm = "D:/Downloads/TfGMgtfsnew_clean.zip",
  rail = "D:/Downloads/gtfs_rail_gm_clean.zip"
)
OUTPUT_ZIP <- "D:/Downloads/gtfs_gm_all_modes.zip"
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


# ---- 1. read input feeds ----------------------------------------------------
feeds <- list()
for (nm in names(FEEDS)) {
  path <- FEEDS[[nm]]
  if (!file.exists(path)) stop("Feed not found: ", path, call. = FALSE)
  message("Reading ", nm, " <- ", path, " ...")
  feeds[[nm]] <- read_gtfs(path)
}


# ---- 2. merge with per-feed ID prefixes -------------------------------------
# Pass the feeds as separate (unnamed) positional arguments via do.call -- the
# documented form `merge_gtfs(a, b, prefix = c(...))`. Handing merge_gtfs a
# single named list instead leaves the prefix unmapped (it resolves to NA),
# producing "NA_<id>" values and cross-feed key collisions.
message("Merging ", length(feeds), " feeds (prefixes: ",
        paste(names(feeds), collapse = ", "), ") ...")
merged <- do.call(merge_gtfs, c(unname(feeds), list(prefix = names(feeds))))


# ---- 2b. normalise the timepoint column -------------------------------------
# Only one feed carries a `timepoint` column, so after merging it is empty for
# every row from the feed that lacked it. An empty timepoint already means
# "times are exact" in GTFS; set it explicitly to 1 so the merged feed does not
# raise a `missing_timepoint_value` warning for every such row.
if (!is.null(merged$stop_times) && "timepoint" %in% names(merged$stop_times)) {
  tp  <- merged$stop_times$timepoint
  gap <- which(is.na(tp) | trimws(as.character(tp)) == "")
  if (length(gap) > 0) {
    fill <- if (is.character(tp)) "1" else 1L
    data.table::set(merged$stop_times, i = gap, j = "timepoint", value = fill)
    message("Filled ", length(gap), " empty timepoint value(s) with 1 (exact).")
  }
}


# ---- 3. integrity checks ----------------------------------------------------
# Confirm the prefixed merge produced unique keys AND consistent references.
problems <- 0L
check_unique <- function(label, ids) {
  ids <- ids[!is.na(ids) & ids != ""]
  dup <- unique(ids[duplicated(ids)])
  if (length(dup) > 0) {
    problems <<- problems + 1L
    message(sprintf("  FAIL  %-32s %d duplicate key(s), e.g. %s",
                    label, length(dup), dup[1]))
  } else message(sprintf("  ok    %s", label))
}
check_ref <- function(label, child, parent) {
  miss <- setdiff(unique(child), unique(parent))
  miss <- miss[!is.na(miss) & miss != ""]
  if (length(miss) > 0) {
    problems <<- problems + 1L
    message(sprintf("  FAIL  %-32s %d unmatched id(s), e.g. %s",
                    label, length(miss), miss[1]))
  } else message(sprintf("  ok    %s", label))
}
message("Integrity checks:")
check_unique("routes.route_id unique",     merged$routes$route_id)
check_unique("trips.trip_id unique",       merged$trips$trip_id)
check_unique("stops.stop_id unique",       merged$stops$stop_id)
check_unique("agency.agency_id unique",    merged$agency$agency_id)
check_unique("calendar.service_id unique", merged$calendar$service_id)
check_ref("trips.route_id -> routes",      merged$trips$route_id,    merged$routes$route_id)
check_ref("trips.service_id -> calendar",  merged$trips$service_id,
          c(merged$calendar$service_id, merged$calendar_dates$service_id))
check_ref("stop_times.trip_id -> trips",   merged$stop_times$trip_id, merged$trips$trip_id)
check_ref("stop_times.stop_id -> stops",   merged$stop_times$stop_id, merged$stops$stop_id)
check_ref("routes.agency_id -> agency",    merged$routes$agency_id,   merged$agency$agency_id)
if (problems > 0) {
  warning(problems, " integrity check(s) failed -- inspect before using the feed.")
} else {
  message("  all checks passed.")
}


# ---- 4. write ---------------------------------------------------------------
message("Writing merged feed to ", OUTPUT_ZIP, " ...")
write_gtfs(merged, OUTPUT_ZIP)


# ---- 5. summary -------------------------------------------------------------
cat("\n", strrep("=", 62), "\n", sep = "")
cat("GTFS MERGE SUMMARY\n")
cat(strrep("=", 62), "\n", sep = "")
cat(sprintf("  %-10s %9s %9s %9s %13s\n",
            "feed", "agency", "routes", "trips", "stop_times"))
for (nm in names(feeds)) {
  g <- feeds[[nm]]
  cat(sprintf("  %-10s %9d %9d %9d %13d\n", nm,
              nrow(g$agency), nrow(g$routes), nrow(g$trips), nrow(g$stop_times)))
}
cat(strrep("-", 62), "\n", sep = "")
cat(sprintf("  %-10s %9d %9d %9d %13d\n", "MERGED",
            nrow(merged$agency), nrow(merged$routes),
            nrow(merged$trips), nrow(merged$stop_times)))
cat("\n  Output: ", OUTPUT_ZIP, "\n", sep = "")
cat("  Validate by adding it to FEEDS in validate_gtfs_feeds.R\n")
cat("  Then place it where PT2MATSimExample reads its GTFS input.\n")
