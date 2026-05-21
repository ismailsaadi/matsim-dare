# get_rail_data.R
# -----------------------------------------------------------------------------
# Download the full GB National Rail timetable (ATOC/CIF format, weekly update)
# from the National Rail Open Data Portal (NRDP) using the UK2GTFS package.
#
# This is step 1 of rebuilding the merged tram + bus + rail GTFS feed for the
# matsim-dare PT pipeline. The output here is the raw ATOC/CIF timetable zip;
# the ATOC -> GTFS conversion (atoc2gtfs) is a separate, later step.
#
# PREREQUISITE -- NRDP credentials (free account at
# https://opendata.nationalrail.co.uk, with an active Timetable feed
# subscription). Store them in your user .Renviron -- NEVER in this script or
# in git. Open .Renviron with:
#     usethis::edit_r_environ()
# and add these two lines (no quotes, no spaces around '='):
#     NRDP_username=your.email@example.com
#     NRDP_password=yourSecretPassword
# Save, then restart R. (This script also re-reads .Renviron defensively so a
# fresh edit is picked up without a restart.)
#
# NOTE: the Rail Delivery Group plans to retire the NRDP portal during 2026 in
# favour of the RDM feeds. If nrdp_timetable() starts returning auth/404
# errors, the NRDP_URL endpoint below will need updating.
#
# Run from the repository root, e.g.:
#     Rscript src/main/java/en/phm/pt/get_rail_data.R
# -----------------------------------------------------------------------------

## ---- configuration ----------------------------------------------------------
# Output location for the downloaded timetable zip (relative to the repo root).
OUT_DIR  <- "D:/Downloads"
OUT_FILE <- "rail_timetable.zip"

# NRDP timetable endpoint -- full national CIF timetable, weekly update.
NRDP_URL <- "https://opendata.nationalrail.co.uk/api/staticfeeds/3.0/timetable"
## ----------------------------------------------------------------------------


# ---- 1. dependencies --------------------------------------------------------
CRAN <- "https://cloud.r-project.org"
if (!requireNamespace("remotes", quietly = TRUE)) {
  install.packages("remotes", repos = CRAN)
}
if (!requireNamespace("UK2GTFS", quietly = TRUE)) {
  message("Installing UK2GTFS from GitHub (ITSLeeds/UK2GTFS) ...")
  remotes::install_github("ITSLeeds/UK2GTFS")
}
library(UK2GTFS)


# ---- 2. credentials ---------------------------------------------------------
# Re-read .Renviron so freshly-added credentials are available in this session
# without a restart.
renviron <- path.expand("~/.Renviron")
if (file.exists(renviron)) readRenviron(renviron)

nrdp_user <- Sys.getenv("NRDP_username")
nrdp_pass <- Sys.getenv("NRDP_password")

if (nrdp_user == "" || nrdp_pass == "") {
  stop(
    "NRDP credentials not found in the environment.\n",
    "  Add NRDP_username and NRDP_password to your .Renviron:\n",
    "      usethis::edit_r_environ()\n",
    "  then restart R and re-run this script.",
    call. = FALSE
  )
}
message("NRDP credentials found for user: ", nrdp_user)


# ---- 3. download ------------------------------------------------------------
if (!dir.exists(OUT_DIR)) dir.create(OUT_DIR, recursive = TRUE)
destfile <- file.path(OUT_DIR, OUT_FILE)

# The national timetable is a large download -- raise the transfer timeout so
# a slow connection does not trip the default 60 s limit.
options(timeout = max(600, getOption("timeout")))

message("Downloading full national rail timetable from NRDP ...")
message("  endpoint : ", NRDP_URL)
message("  destfile : ", destfile)

tryCatch(
  nrdp_timetable(
    destfile = destfile,
    username = nrdp_user,
    password = nrdp_pass,
    url      = NRDP_URL
  ),
  error = function(e) {
    stop(
      "nrdp_timetable() failed: ", conditionMessage(e), "\n",
      "  Check that the NRDP credentials are correct and the account has an\n",
      "  active Timetable feed subscription. Note the NRDP portal is being\n",
      "  retired during 2026 in favour of the RDM feeds.",
      call. = FALSE
    )
  }
)


# ---- 4. report --------------------------------------------------------------
if (file.exists(destfile)) {
  size_mb <- round(file.info(destfile)$size / 1024^2, 1)
  message("Done. Saved ", destfile, " (", size_mb, " MB).")
  message("Archive contents:")
  print(utils::unzip(destfile, list = TRUE))
} else {
  stop("Download appears to have failed -- ", destfile, " was not created.",
       call. = FALSE)
}
