# convert_rail_gtfs.R
# -----------------------------------------------------------------------------
# Convert the GB National Rail timetable (ATOC/CIF) to GTFS and clip it to the
# Greater Manchester area, for the matsim-dare PT pipeline.
#
# Steps:
#   1. atoc2gtfs()    -- convert rail_timetable.zip (ATOC/CIF) to a GB-wide GTFS.
#   2. filter_by_sf() -- clip that GTFS to Greater Manchester + a 50 km buffer.
#
# Unlike the TfGM tram/bus feed (already GM-scoped, so not clipped), the rail
# feed is national, so clipping here is necessary. Whole trips that touch the
# buffered area are kept intact -- a Manchester-bound long-distance train keeps
# all of its stops, including ones outside GM.
#
# REQUIREMENTS:
#   - UK2GTFS (atoc2gtfs, gtfs_write), and gtfstools + sf.
#   - DISK SPACE: atoc2gtfs() stages large temp files while parsing the ~525 MB
#     national .MCA timetable. If the system drive (C:) is short on space,
#     point R's temp dir at a roomier drive BEFORE starting R -- add
#     `TMPDIR=D:/Rtemp` to .Renviron (or set the TMPDIR env var) -- then
#     restart R. Check with tempdir().
#
# Run from the repository root:
#   Rscript src/main/java/en/phm/pt/convert_rail_gtfs.R
# -----------------------------------------------------------------------------

## ---- configuration ----------------------------------------------------------
RAIL_CIF    <- "D:/Downloads/rail_timetable.zip"  # ATOC/CIF input (get_rail_data.R)
GB_GTFS_ZIP <- "D:/Downloads/gtfs_rail_gb.zip"    # intermediate: full-GB rail GTFS
OUTPUT_ZIP  <- "D:/Downloads/gtfs_rail_gm.zip"    # final: clipped to GM + buffer

# Re-run the (slow) ATOC -> GTFS conversion even if GB_GTFS_ZIP already exists.
# Leave FALSE to reuse the GB GTFS and just re-clip.
FORCE_CONVERT <- FALSE
NCORES        <- max(1L, parallel::detectCores() - 1L)  # cores for atoc2gtfs

# Clip extent. Greater Manchester bounding box by default; set BOUNDARY_FILE to
# an sf-readable file (.gpkg / .shp / .geojson) to clip against a precise
# boundary instead.
BOUNDARY_FILE <- NULL
GM_BBOX_WGS84 <- c(xmin = -2.75, ymin = 53.30, xmax = -1.90, ymax = 53.70)
BUFFER_M      <- 50000          # 50 km buffer, applied in metres (EPSG:27700)
## ----------------------------------------------------------------------------


# ---- dependencies -----------------------------------------------------------
CRAN <- "https://cloud.r-project.org"
if (!requireNamespace("remotes", quietly = TRUE)) {
  install.packages("remotes", repos = CRAN)
}
if (!requireNamespace("UK2GTFS", quietly = TRUE)) {
  message("Installing UK2GTFS from GitHub (ITSLeeds/UK2GTFS) ...")
  remotes::install_github("ITSLeeds/UK2GTFS")
}
for (pkg in c("gtfstools", "sf")) {
  if (!requireNamespace(pkg, quietly = TRUE)) {
    message("Installing ", pkg, " ...")
    install.packages(pkg, repos = CRAN)
  }
}


# ---- temp-space warning -----------------------------------------------------
if (grepl("^[Cc]:", tempdir())) {
  message("------------------------------------------------------------------")
  message("WARNING: R's temp directory is on C: -> ", tempdir())
  message("  atoc2gtfs() stages large temp files. If C: is low on space the")
  message("  conversion will fail. Set TMPDIR to another drive, then restart R.")
  message("------------------------------------------------------------------")
}


# ---- helper -----------------------------------------------------------------
feed_counts <- function(g) {
  c(routes = nrow(g$routes),
    trips  = nrow(g$trips),
    stops  = nrow(g$stops))
}


# ---- 1. ATOC/CIF -> GB-wide GTFS --------------------------------------------
if (FORCE_CONVERT || !file.exists(GB_GTFS_ZIP)) {
  if (!file.exists(RAIL_CIF)) {
    stop("Rail CIF not found: ", RAIL_CIF,
         "  (run get_rail_data.R first).", call. = FALSE)
  }
  message("Converting ATOC/CIF -> GTFS with atoc2gtfs() (ncores = ", NCORES, ") ...")
  message("  This is slow -- the national .MCA timetable is large.")
  rail <- UK2GTFS::atoc2gtfs(path_in = RAIL_CIF, ncores = NCORES)

  message("Writing full-GB rail GTFS to ", GB_GTFS_ZIP, " ...")
  UK2GTFS::gtfs_write(
    rail,
    folder = dirname(GB_GTFS_ZIP),
    name   = tools::file_path_sans_ext(basename(GB_GTFS_ZIP))
  )
} else {
  message("Reusing existing GB rail GTFS: ", GB_GTFS_ZIP)
  message("  (set FORCE_CONVERT = TRUE to rebuild it.)")
}


# ---- 2. clip to Greater Manchester + buffer ---------------------------------
message("Reading GB rail GTFS for clipping ...")
gtfs   <- gtfstools::read_gtfs(GB_GTFS_ZIP)
before <- feed_counts(gtfs)

message("Clipping to Greater Manchester + ", BUFFER_M / 1000, " km buffer ...")
if (!is.null(BOUNDARY_FILE) && file.exists(BOUNDARY_FILE)) {
  gm <- sf::st_geometry(sf::st_read(BOUNDARY_FILE, quiet = TRUE))
} else {
  bb   <- GM_BBOX_WGS84
  ring <- matrix(c(bb[["xmin"]], bb[["ymin"]],
                   bb[["xmax"]], bb[["ymin"]],
                   bb[["xmax"]], bb[["ymax"]],
                   bb[["xmin"]], bb[["ymax"]],
                   bb[["xmin"]], bb[["ymin"]]),
                 ncol = 2, byrow = TRUE)
  gm <- sf::st_sfc(sf::st_polygon(list(ring)), crs = 4326)
}
# Buffer in EPSG:27700 (British National Grid, metres), then back to WGS84 --
# GTFS coordinates are lon/lat.
gm      <- sf::st_transform(gm, 27700)
clip_sf <- sf::st_as_sf(sf::st_transform(sf::st_buffer(gm, BUFFER_M), 4326))

gtfs  <- gtfstools::filter_by_sf(gtfs, clip_sf, keep = TRUE)
after <- feed_counts(gtfs)


# ---- 3. write ---------------------------------------------------------------
message("Writing clipped rail GTFS to ", OUTPUT_ZIP, " ...")
gtfstools::write_gtfs(gtfs, OUTPUT_ZIP)

cat("\n", strrep("=", 62), "\n", sep = "")
cat("RAIL GTFS  --  GB  ->  Greater Manchester + ", BUFFER_M / 1000, " km\n", sep = "")
cat(strrep("=", 62), "\n", sep = "")
for (k in names(before)) {
  cat(sprintf("  %-7s %9s  ->  %9s\n", k, before[[k]], after[[k]]))
}
cat("\n  GB GTFS : ", GB_GTFS_ZIP, "\n", sep = "")
cat("  GM GTFS : ", OUTPUT_ZIP, "\n", sep = "")
cat("  Validate by adding it to FEEDS in validate_gtfs_feeds.R\n")
