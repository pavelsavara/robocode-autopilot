package cz.zamboch.autopilot.pipeline.features;

import cz.zamboch.autopilot.core.FileType;
import cz.zamboch.autopilot.core.Whiteboard;
import cz.zamboch.autopilot.core.features.IdentityFeatures;
import cz.zamboch.autopilot.pipeline.CsvRowWriter;
import cz.zamboch.autopilot.pipeline.IOfflineFeatures;

/**
 * Offline extension of IdentityFeatures — emits per-battle constants into scores.csv.
 * Each round row carries the same values; notebooks can drop_duplicates on battle_id.
 *
 * Columns:
 *   opponent_name_hash       — FNV-1a of full name "BotId Version"
 *   opponent_bot_id_hash     — FNV-1a of part before first space (survives version bumps)
 *   opponent_version_hash    — FNV-1a of part after first space (0 if no version)
 *   battlefield_width        — px
 *   battlefield_height       — px
 *   gun_cooling_rate         — energy/tick (typically 0.1)
 *   num_rounds_total         — total rounds in this match
 */
public final class IdentityOfflineFeatures extends IdentityFeatures implements IOfflineFeatures {

    public FileType getFileType() {
        return FileType.SCORES;
    }

    public void writeColumnNames(CsvRowWriter row) {
        row.writeHeaders(
                "opponent_name_hash",
                "opponent_bot_id_hash",
                "opponent_version_hash",
                "battlefield_width",
                "battlefield_height",
                "gun_cooling_rate",
                "num_rounds_total");
    }

    public void writeRowValues(CsvRowWriter row, Whiteboard wb) {
        // Hashes computed on-demand so they emit even if no scan ever populated
        // the in-game feature array (defensive — Player always sets a name).
        row.writeRaw(Integer.toString(IdentityFeatures.fnv1a32(wb.getOpponentName())));
        row.writeRaw(Integer.toString(IdentityFeatures.fnv1a32(wb.getOpponentBotId())));
        row.writeRaw(Integer.toString(IdentityFeatures.fnv1a32(wb.getOpponentVersion())));
        row.writeRaw(Integer.toString(wb.getBattlefieldWidth()));
        row.writeRaw(Integer.toString(wb.getBattlefieldHeight()));
        row.writeRaw(String.format("%.4f", wb.getGunCoolingRate()));
        row.writeRaw(Integer.toString(wb.getNumRounds()));
    }
}
