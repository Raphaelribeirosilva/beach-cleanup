"""Refresh the bundled IBGE geography; never called at application startup."""
import gzip
import json
import urllib.request
from pathlib import Path

BASE = 'https://servicodados.ibge.gov.br/api/v1/localidades/'


def fetch(route):
    with urllib.request.urlopen(BASE + route, timeout=45) as response:
        data = response.read()
        if data.startswith(b'\x1f\x8b'):
            data = gzip.decompress(data)
        return json.loads(data)


states = fetch('estados?orderBy=nome')
cities = fetch('municipios?orderBy=nome')
result = []
for state in states:
    names = []
    for city in cities:
        region = city.get('microrregiao')
        uf = region['mesorregiao']['UF'] if region else city['regiao-imediata']['regiao-intermediaria']['UF']
        if uf['sigla'] == state['sigla']:
            names.append(city['nome'])
    result.append({'uf': state['sigla'], 'name': state['nome'], 'cities': names})
assert len(result) == 27 and sum(len(s['cities']) for s in result) > 5500
destination = Path(__file__).resolve().parents[1] / 'src/main/resources/static/locations.json'
destination.write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
print(f"IBGE: {len(result)} UFs; {sum(len(s['cities']) for s in result)} municipios")
