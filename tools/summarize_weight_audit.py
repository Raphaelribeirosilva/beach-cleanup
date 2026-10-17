"""Summarize a complete /admin/data-quality/rounding export using exact decimal arithmetic."""
import argparse
from decimal import Decimal, InvalidOperation, localcontext
import json
from pathlib import Path


def summarize(records):
    with localcontext() as context:
        context.prec = 500  # Covers the full original DOUBLE exponent range and DECIMAL(38,3).
        seen, groups = set(), {}
        for row in records:
            identity = row['collectionId']
            if identity in seen:
                raise ValueError(f'Coleta duplicada na exportação: {identity}')
            seen.add(identity)
            key = str(row.get('volunteerId')) if row.get('volunteerId') is not None else 'sem_proprietario'
            group = groups.setdefault(key, dict(records=0, missingWeights=0, originalKg=Decimal(0),
                                               convertedKg=Decimal(0), differenceKg=Decimal(0)))
            group['records'] += 1
            values = [row.get(name) for name in ('originalWeight', 'roundedWeight', 'deltaWeight')]
            if all(value is None for value in values):
                group['missingWeights'] += 1
                continue
            if not all(isinstance(value, str) for value in values):
                raise ValueError(f'Use a exportação completa e os valores decimais em texto: coleta {identity}')
            try:
                original, converted, difference = map(Decimal, values)
            except InvalidOperation as error:
                raise ValueError(f'Valor decimal inválido: coleta {identity}') from error
            if not all(value.is_finite() for value in (original, converted, difference)):
                raise ValueError(f'Peso não finito: coleta {identity}')
            if converted - original != difference:
                raise ValueError(f'Diferença não confere: coleta {identity}')
            group['originalKg'] += original
            group['convertedKg'] += converted
            group['differenceKg'] += difference
        total = {key: sum((group[key] for group in groups.values()), Decimal(0))
                 for key in ('originalKg', 'convertedKg', 'differenceKg')}
        total.update(records=len(seen), missingWeights=sum(group['missingWeights'] for group in groups.values()))
        result = dict(snapshot='V7; proprietários e pesos na migração, antes de edições posteriores',
                      total=total, byOwner=groups)
        # Fixed-point strings keep the summary exact when opened by a browser/spreadsheet.
        for item in [total, *groups.values()]:
            for key in ('originalKg', 'convertedKg', 'differenceKg'):
                item[key] = format(item[key], 'f')
        return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path, help='JSON array with every page of the rounding endpoint')
    args = parser.parse_args()
    try:
        print(json.dumps(summarize(json.loads(args.export.read_text(encoding='utf-8-sig'))), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'Auditoria inválida: {error}\n')
