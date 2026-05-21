# validate_gtfs_feeds.R
# -----------------------------------------------------------------------------
# Validate raw GTFS feeds with the MobilityData Canonical GTFS Validator,
# wrapped by the gtfstools R package.
#
# This is a quality-check step before merging the tram/bus and rail feeds into
# a single GTFS for the matsim-dare PT pipeline. For each feed it:
#   1. lists the raw files in the zip and the parsed tables (rows x cols);
#   2. runs the canonical validator (business-rule + referential checks);
#   3. prints a console summary of ERROR / WARNING / INFO notices and points
#      to the full report.html.
#
# REQUIREMENTS:
#   - Java 11+ on PATH -- the validator is a Java tool (this project already
#     uses Java 21, so `java -version` should work).
#   - Internet access on the first run, to download the validator jar.
#
# NOTE: only GTFS feeds can be validated here. rail_timetable.zip is ATOC/CIF,
# NOT GTFS -- it must be converted with atoc2gtfs() first, then its GTFS zip
# can be added to FEEDS below.
#
# Run from the repository root:
#   Rscript src/main/java/en/phm/pt/validate_gtfs_feeds.R
# -----------------------------------------------------------------------------

## ---- configuration ----------------------------------------------------------
# Directory holding the GTFS zip(s).
GTFS_DIR <- "D:/Downloads"

# GTFS feeds to validate (file names within GTFS_DIR). Add the converted rail
# GTFS here once atoc2gtfs() has produced it, e.g. "gtfs_rail.zip".
FEEDS <- c("gtfs_gm_all_modes.zip")

# Where to keep the downloaded validator jar (reused across runs).
VALIDATOR_DIR <- file.path(GTFS_DIR, "gtfs_validator")

# Where validation reports are written (one sub-folder per feed).
OUTPUT_DIR <- file.path(GTFS_DIR, "gtfs_validation")
## ----------------------------------------------------------------------------


# ---- 1. dependencies --------------------------------------------------------
CRAN <- "https://cloud.r-project.org"
for (pkg in c("gtfstools", "jsonlite")) {
  if (!requireNamespace(pkg, quietly = TRUE)) {
    message("Installing ", pkg, " ...")
    install.packages(pkg, repos = CRAN)
  }
}
library(gtfstools)


# ---- 2. Java check ----------------------------------------------------------
java_ver <- tryCatch(
  system2("java", "-version", stdout = TRUE, stderr = TRUE),
  error = function(e) NULL
)
if (is.null(java_ver) || length(java_ver) == 0) {
  stop("Java was not found on PATH. The MobilityData validator needs Java 11+.",
       call. = FALSE)
}
message("Java detected: ", java_ver[1])


# ---- 3. download the validator ---------------------------------------------
if (!dir.exists(VALIDATOR_DIR)) dir.create(VALIDATOR_DIR, recursive = TRUE)
message("Locating the MobilityData GTFS validator ...")
validator <- tryCatch(
  download_validator(VALIDATOR_DIR),   # no-op if already present (force = FALSE)
  error = function(e) {
    stop("Could not obtain the validator jar: ", conditionMessage(e), "\n",
         "  The first run needs internet access to download it.",
         call. = FALSE)
  }
)
message("  validator: ", validator)


# ---- 4. validate each feed --------------------------------------------------
if (!dir.exists(OUTPUT_DIR)) dir.create(OUTPUT_DIR, recursive = TRUE)

sev_rank <- c(ERROR = 1L, WARNING = 2L, INFO = 3L)
overall  <- list()
rule     <- function() cat(strrep("=", 78), "\n", sep = "")

for (feed in FEEDS) {
  feed_path <- file.path(GTFS_DIR, feed)
  cat("\n"); rule()
  cat("FEED: ", feed_path, "\n", sep = ""); rule()

  if (!file.exists(feed_path)) {
    message("  SKIPPED -- file not found.")
    overall[[feed]] <- "missing"
    next
  }

  ## 4a. raw-file inventory ---------------------------------------------------
  cat("\n-- archive contents --\n")
  print(utils::unzip(feed_path, list = TRUE), row.names = FALSE)

  cat("\n-- parsed tables (rows x cols) --\n")
  gtfs <- tryCatch(read_gtfs(feed_path), error = function(e) e)
  if (inherits(gtfs, "error")) {
    message("  read_gtfs() failed: ", conditionMessage(gtfs))
    message("  (the validator still runs on the raw zip and will report why.)")
  } else {
    print(data.frame(
      file = paste0(names(gtfs), ".txt"),
      rows = vapply(gtfs, nrow, integer(1)),
      cols = vapply(gtfs, ncol, integer(1)),
      row.names = NULL
    ), row.names = FALSE)
  }

  ## 4b. run the canonical validator -----------------------------------------
  cat("\n-- validation --\n")
  out_dir <- file.path(OUTPUT_DIR, tools::file_path_sans_ext(feed))
  if (!dir.exists(out_dir)) dir.create(out_dir, recursive = TRUE)

  val_ok <- tryCatch({
    validate_gtfs(
      gtfs           = feed_path,
      output_path    = out_dir,
      validator_path = validator,
      overwrite      = TRUE,
      html_preview   = FALSE,
      pretty_json    = TRUE,
      quiet          = TRUE
    )
    TRUE
  }, error = function(e) {
    message("  validate_gtfs() failed: ", conditionMessage(e))
    FALSE
  })
  if (!val_ok) { overall[[feed]] <- "validation failed"; next }

  ## 4c. summarise report.json -----------------------------------------------
  report_json <- file.path(out_dir, "report.json")
  if (!file.exists(report_json)) {
    message("  No report.json produced -- inspect ",
            file.path(out_dir, "system_errors.json"))
    overall[[feed]] <- "no report"
    next
  }

  rep     <- jsonlite::fromJSON(report_json)
  notices <- as.data.frame(rep$notices)

  if (nrow(notices) == 0) {
    cat("  Clean -- no notices.\n")
    overall[[feed]] <- "clean"
  } else {
    notices <- notices[order(sev_rank[notices$severity],
                             -notices$totalNotices), ]
    by_sev <- tapply(notices$totalNotices, notices$severity, sum)
    get    <- function(s) if (is.na(by_sev[s])) 0L else by_sev[[s]]
    cat(sprintf("  ERROR: %d | WARNING: %d | INFO: %d\n",
                get("ERROR"), get("WARNING"), get("INFO")))
    cat("\n  notices by code:\n")
    print(data.frame(
      severity = notices$severity,
      code     = notices$code,
      count    = notices$totalNotices,
      row.names = NULL
    ), row.names = FALSE)
    overall[[feed]] <-
      if (any(notices$severity == "ERROR")) "HAS ERRORS" else "warnings only"
  }

  cat("\n  full report: ", file.path(out_dir, "report.html"), "\n", sep = "")
}


# ---- 5. overall summary -----------------------------------------------------
cat("\n"); rule(); cat("SUMMARY\n"); rule()
for (feed in names(overall)) {
  cat(sprintf("  %-28s %s\n", feed, overall[[feed]]))
}
