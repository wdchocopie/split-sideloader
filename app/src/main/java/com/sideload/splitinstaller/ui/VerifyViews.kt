package com.sideload.splitinstaller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sideload.splitinstaller.R
import com.sideload.splitinstaller.core.bundle.Finding
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.sign.CertInfo
import com.sideload.splitinstaller.core.verify.Verdict
import com.sideload.splitinstaller.core.verify.VerifyReport

/** The answer first: does this install work? Coloured by verdict, with the reason. */
@Composable
fun VerdictHero(report: VerifyReport, modifier: Modifier = Modifier) {
    val tone = report.verdict.tone()
    val title = stringResource(
        when (report.verdict) {
            Verdict.OK -> R.string.verdict_ok_title
            Verdict.WARN -> R.string.verdict_warn_title
            Verdict.BROKEN -> R.string.verdict_broken_title
            Verdict.NOT_INSTALLED -> R.string.verdict_absent
            Verdict.UNKNOWN -> R.string.verdict_unknown
        }
    )
    val lead = report.findings.firstOrNull { it.severity == Severity.ERROR }
        ?: report.findings.firstOrNull { it.severity == Severity.WARN }
    val reason = lead?.localized()
        ?: stringResource(if (report.nativeExpected) R.string.verdict_ok_body else R.string.verdict_ok_nonnative)

    AppCard(
        modifier = modifier,
        color = tone.container(),
        contentColor = tone.onContainer(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(20.dp),
        spacing = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(tone.onContainer().copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(report.verdict.icon(), null, Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text(reason, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroFact("primaryCpuAbi", report.primaryCpuAbi ?: "null", Modifier.weight(1f))
            HeroFact(stringResource(R.string.label_native_libs), report.nativeLibCount.toString(), Modifier.weight(1f))
            HeroFact(stringResource(R.string.label_splits), report.splitNames.size.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroFact(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (AppTheme.isDark) 0.22f else 0.55f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeLibsCard(report: VerifyReport) {
    AppCard {
        CardTitle(stringResource(R.string.card_native), Icons.Rounded.Memory)
        KeyValue(
            "primaryCpuAbi",
            report.primaryCpuAbi ?: "null",
            mono = true,
            valueColor = if (report.primaryCpuAbi == null && report.nativeExpected) Tone.DANGER.accent()
            else MaterialTheme.colorScheme.onSurface,
        )
        report.abiSource?.let { KeyValue(stringResource(R.string.label_read_via), it) }
        KeyValue(
            stringResource(R.string.label_apk_abis),
            report.apkAbis.joinToString(", ").ifBlank { stringResource(R.string.none_native) },
            mono = true,
        )
        KeyValue(
            "extractNativeLibs",
            stringResource(if (report.extractNativeLibs) R.string.extract_yes else R.string.extract_no),
        )
        report.engine?.let { KeyValue(stringResource(R.string.label_engine), it.label) }
        KeyValue("nativeLibraryDir", report.nativeLibraryDir ?: "—", mono = true)
        if (report.nativeLibs.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                report.nativeLibs.take(12).forEach { MetaPill(it, mono = true) }
                if (report.nativeLibs.size > 12) MetaPill("+" + (report.nativeLibs.size - 12))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalledApksCard(report: VerifyReport) {
    AppCard {
        CardTitle(
            stringResource(R.string.card_installed_apks, report.apks.size),
            Icons.Rounded.Inventory2,
            trailing = { Text(humanSize(report.totalSize), style = MaterialTheme.typography.labelLarge) },
        )
        report.apks.forEachIndexed { i, apk ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        apk.splitName ?: "base.apk",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        MetaPill(humanSize(apk.size))
                        apk.abis.forEach { MetaPill(it, mono = true, tone = Tone.PRIMARY) }
                        if (apk.soCount > 0) MetaPill("${apk.soCount} .so")
                        if (!apk.readable) MetaPill(stringResource(R.string.unreadable), tone = Tone.WARNING)
                    }
                }
            }
        }
    }
}

@Composable
fun FindingsCard(findings: List<Finding>, title: String = stringResource(R.string.section_checks)) {
    if (findings.isEmpty()) return
    AppCard {
        CardTitle(title, Icons.Rounded.Checklist)
        findings.forEach { FindingRow(it.severity, it.localized()) }
    }
}

/** First eight bytes by default — enough to compare by eye; tap for all thirty-two. */
@Composable
fun Fingerprint(cert: CertInfo, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    var full by androidx.compose.runtime.saveable.rememberSaveable(cert.sha256) { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { full = !full }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "SHA-256",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(128.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (full) cert.formatted() else cert.formatted(8) + " …",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CertDetails(cert: CertInfo, schemes: List<String> = emptyList(), v1File: String? = null) {
    KeyValue(stringResource(R.string.label_signer), cert.commonName)
    Fingerprint(cert)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cert.isPlayAppSigning) StatusPill("Play App Signing", Tone.INFO, Icons.Rounded.Fingerprint)
        schemes.forEach { MetaPill(it) }
        v1File?.let { MetaPill(it.substringAfterLast('/'), mono = true) }
    }
}
