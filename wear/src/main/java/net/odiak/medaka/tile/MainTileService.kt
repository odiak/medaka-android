package net.odiak.medaka.tile

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.protolayout.ActionBuilders.AndroidActivity
import androidx.wear.protolayout.ActionBuilders.LaunchAction
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.tools.LayoutRootPreview
import com.google.android.horologist.compose.tools.buildDeviceParameters
import com.google.android.horologist.tiles.SuspendingTileService
import net.odiak.medaka.ListeningService
import net.odiak.medaka.presentation.MainActivity

private const val RESOURCES_VERSION = "0"

/**
 * Skeleton for a tile with no images.
 */
@OptIn(ExperimentalHorologistApi::class)
class MainTileService : SuspendingTileService() {

    override suspend fun resourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ResourceBuilders.Resources {
        return ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    override suspend fun tileRequest(
        requestParams: RequestBuilders.TileRequest
    ): TileBuilders.Tile {
        ListeningService.checkData(this)

        val singleTileTimeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder().setLayout(
                LayoutElementBuilders.Layout.Builder().setRoot(tileLayout(this)).build()
            ).build()
        ).build()

        return TileBuilders.Tile.Builder().setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(singleTileTimeline)
            .setFreshnessIntervalMillis(5 * 60 * 1000)
            .build()
    }
}

private fun tileLayout(context: Context): LayoutElementBuilders.LayoutElement {
    val data = ListeningService.lastData.value

    val modifier = (
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(
                    ModifiersBuilders.Clickable.Builder().setOnClick(
                        LaunchAction.Builder().setAndroidActivity(
                            AndroidActivity.Builder()
                                .setClassName(MainActivity::class.java.name)
                                .setPackageName(context.packageName)
                                .build()
                        ).build()
                    ).build()
                )
                .build()
            )

    val color = argb(Color.argb(0xff, 0xf5, 0xf5, 0xf5))

    val content = if (data == null) {
        Text.Builder(context, "No data").setModifiers(modifier).setColor(color).build()
    } else {
        Column.Builder()
            .setModifiers(modifier)
            .addContent(
                Text.Builder(context, "${data.lastSG} ${data.lastSGDiff}")
                    .setColor(color)
                    .build()
            )
            .addContent(Text.Builder(context, "at ${data.lastSGTime}").setColor(color).build())
            .build()
    }

    return PrimaryLayout.Builder(buildDeviceParameters(context.resources)).setContent(
        content
    ).build()
}

@Preview(
    device = Devices.WEAR_OS_SMALL_ROUND,
    showSystemUi = true,
    backgroundColor = 0xff000000,
    showBackground = true
)
@Composable
fun TilePreview() {
    LayoutRootPreview(root = tileLayout(LocalContext.current))
}