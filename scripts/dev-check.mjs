const checks = [
  ["Backend", "http://127.0.0.1:8080/health"],
  ["Frontend", "http://127.0.0.1:5173"],
  ["Vite proxy", "http://127.0.0.1:5173/api/health"]
];

for (const [name, url] of checks) {
  const response = await fetch(url).catch(() => undefined);
  if (!response?.ok) {
    console.error(`${name} is unavailable at ${url}`);
    process.exit(1);
  }
  console.log(`${name}: ${response.status} ${url}`);
}
