import fs from 'node:fs';

// Native image gate. Java is reviewed separately; all findings remain in the report.
const report = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!Array.isArray(report.matches) || !report.distro?.name || !report.source) {
  throw new Error('Incomplete image scan report');
}
const blocked = report.matches.filter(({ artifact, vulnerability }) =>
  artifact.type !== 'java-archive'
  && ['High', 'Critical'].includes(vulnerability.severity)
  && vulnerability.fix?.state === 'fixed'
  && vulnerability.fix.versions?.length > 0);
const counts = {};
for (const { vulnerability } of report.matches) {
  counts[vulnerability.severity] = (counts[vulnerability.severity] ?? 0) + 1;
}
console.log(JSON.stringify({ distro: report.distro, allFindings: counts,
  blockingNativeFindings: blocked.map(({ artifact, vulnerability }) => ({
    package: artifact.name, installed: artifact.version, id: vulnerability.id,
    severity: vulnerability.severity, fixedIn: vulnerability.fix.versions,
  })) }, null, 2));
if (blocked.length) process.exitCode = 1;
