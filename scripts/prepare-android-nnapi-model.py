#!/usr/bin/env python3
"""Rewrite YOLO26 Split nodes into NNAPI-friendly Slice nodes.

The Android NNAPI execution provider in the supported mobile runtime can
partition this model family, but rejects the ONNX Split form that supplies
split sizes as the second input.  Slice is semantically equivalent for the
static channel/sequence partitions emitted by the exporter and remains a
normal ONNX graph for CPU, DirectML, and NNAPI execution.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, dest="input_path")
    parser.add_argument("--output", required=True, type=Path, dest="output_path")
    return parser.parse_args()


def as_int_list(initializer: onnx.TensorProto) -> list[int]:
    values = numpy_helper.to_array(initializer)
    if values.ndim != 1 or values.size == 0:
        raise ValueError(f"Split sizes must be a non-empty 1-D tensor: {initializer.name}")
    return [int(value) for value in values.tolist()]


def rewrite_model(input_path: Path, output_path: Path) -> int:
    if input_path.resolve() == output_path.resolve():
        raise ValueError("Input and output paths must be different")

    model = onnx.load(str(input_path))
    initializer_by_name = {initializer.name: initializer for initializer in model.graph.initializer}
    split_initializer_names: set[str] = set()
    rewritten_count = 0
    rewritten_nodes: list[onnx.NodeProto] = []

    for node_index, node in enumerate(model.graph.node):
        if node.op_type != "Split" or len(node.input) < 2 or node.input[1] not in initializer_by_name:
            rewritten_nodes.append(node)
            continue

        split_sizes = as_int_list(initializer_by_name[node.input[1]])
        if len(split_sizes) != len(node.output) or any(size <= 0 for size in split_sizes):
            raise ValueError(
                f"Split {node.name or node_index} has {len(node.output)} outputs but sizes {split_sizes}"
            )

        axis = next((attribute.i for attribute in node.attribute if attribute.name == "axis"), 0)
        offset = 0
        for output_index, (output_name, split_size) in enumerate(zip(node.output, split_sizes)):
            prefix = (node.name or f"split_{node_index}").replace("/", "_")
            prefix = f"{prefix}_slice_{output_index}"
            starts_name = f"{prefix}_starts"
            ends_name = f"{prefix}_ends"
            axes_name = f"{prefix}_axes"
            steps_name = f"{prefix}_steps"
            values = (
                (starts_name, [offset]),
                (ends_name, [offset + split_size]),
                (axes_name, [axis]),
                (steps_name, [1]),
            )
            for name, value in values:
                model.graph.initializer.append(
                    numpy_helper.from_array(np.asarray(value, dtype=np.int64), name=name)
                )
            rewritten_nodes.append(
                helper.make_node(
                    "Slice",
                    [node.input[0], starts_name, ends_name, axes_name, steps_name],
                    [output_name],
                    name=prefix,
                )
            )
            offset += split_size

        split_initializer_names.add(node.input[1])
        rewritten_count += 1

    model.graph.ClearField("node")
    model.graph.node.extend(rewritten_nodes)

    used_inputs = {input_name for node in model.graph.node for input_name in node.input}
    retained_initializers = [
        initializer
        for initializer in model.graph.initializer
        if initializer.name not in split_initializer_names or initializer.name in used_inputs
    ]
    model.graph.ClearField("initializer")
    model.graph.initializer.extend(retained_initializers)

    onnx.checker.check_model(model)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(output_path))
    print(
        f"Prepared {output_path} from {input_path}: "
        f"rewrote {rewritten_count} Split nodes, {len(model.graph.node)} total nodes, "
        f"{os.path.getsize(output_path)} bytes"
    )
    return rewritten_count


def main() -> None:
    args = parse_args()
    rewrite_model(args.input_path, args.output_path)


if __name__ == "__main__":
    main()
