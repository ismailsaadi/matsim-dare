"""
Plot flood depth extent for Greater Manchester from a GeoTIFF.

Raster: single-band float32, EPSG:27700, values in metres (0 = dry).
Buildings shapefile used as background; flooded pixels drawn at alpha=0.75.
"""

import numpy as np
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
import rasterio
import geopandas as gpd

TIF_PATH      = r"D:\Downloads\Manchester_2050_FloodMaps\R1C1_Flood_TIF\R1_C1_T39_390min.tif"
BUILDINGS_SHP = r"D:\Downloads\building_green_shp\building_green_shp\Buildings_final.shp"


def load_flood_raster(path):
    with rasterio.open(path) as src:
        data = src.read(1).astype(np.float32)
        extent = [src.bounds.left, src.bounds.right, src.bounds.bottom, src.bounds.top]
        crs = src.crs
    data[data <= 0] = np.nan
    return data, extent, crs


def plot(data, extent, crs):
    fig, ax = plt.subplots(figsize=(12, 9))

    # --- Background: buildings ---
    buildings = gpd.read_file(BUILDINGS_SHP)
    buildings.plot(ax=ax, facecolor="#d9d0c9", edgecolor="#b0a89f", linewidth=0.2, zorder=1)

    # --- Flood layer ---
    cmap = plt.cm.Blues.copy()
    cmap.set_bad(alpha=0)          # dry / no-flood cells fully transparent

    vmax = np.nanpercentile(data, 99)
    img = ax.imshow(
        data,
        cmap=cmap,
        norm=mcolors.Normalize(vmin=0, vmax=vmax),
        extent=extent,
        origin="upper",
        interpolation="nearest",
        alpha=0.75,
        zorder=2,
    )

    ax.set_xlim(extent[0], extent[1])
    ax.set_ylim(extent[2], extent[3])

    cbar = fig.colorbar(img, ax=ax, fraction=0.03, pad=0.02)
    cbar.set_label("Flood depth (m)", fontsize=11)

    ax.set_title("Greater Manchester — Flood Extent\nR1_C1_T39 @ 390 min", fontsize=13)
    ax.set_xlabel("Easting (m, EPSG:27700)")
    ax.set_ylabel("Northing (m, EPSG:27700)")
    ax.ticklabel_format(style="plain")
    ax.set_facecolor("#f5f0eb")    # background colour for areas with no buildings

    plt.tight_layout()
    plt.savefig("flood_extent_R1C1_T39_390min.png", dpi=150)
    plt.show()
    print("Saved: flood_extent_R1C1_T39_390min.png")


if __name__ == "__main__":
    data, extent, crs = load_flood_raster(TIF_PATH)
    print(f"Flooded pixels: {np.sum(~np.isnan(data)):,}  |  "
          f"Max depth: {np.nanmax(data):.2f} m  |  "
          f"99th pct: {np.nanpercentile(data, 99):.2f} m")
    plot(data, extent, crs)
