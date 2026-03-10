"""Minimal pydantic mock for pyatv on Android.

pyatv only uses pydantic for settings/config models (BaseModel, Field, field_validator).
The core AirPlay protocol code doesn't use pydantic at all. This mock provides just
enough to let pyatv's settings module load without the real pydantic (which requires
the Rust-based pydantic-core package not available on Android).
"""
from typing import Any, Optional


def Field(default=None, *, default_factory=None, **kwargs):
    """Mock Field that returns default or calls default_factory."""
    if default_factory is not None:
        return default_factory()
    return default


def field_validator(*fields, **kwargs):
    """Mock field_validator decorator - just returns the function as-is."""
    def decorator(func):
        return classmethod(func) if not isinstance(func, classmethod) else func
    return decorator


class _ModelMeta(type):
    """Metaclass that handles BaseModel initialization with type annotations."""
    def __new__(mcs, name, bases, namespace, **kwargs):
        # Remove pydantic-specific kwargs like extra="ignore"
        kwargs.pop('extra', None)
        cls = super().__new__(mcs, name, bases, namespace)

        # Collect annotations from all bases and current class
        all_annotations = {}
        for base in reversed(cls.__mro__):
            if hasattr(base, '__annotations__'):
                all_annotations.update(base.__annotations__)
        cls._field_annotations = all_annotations
        return cls


class BaseModel(metaclass=_ModelMeta):
    """Minimal BaseModel mock."""

    model_config: dict = {}
    model_fields: dict = {}

    def __init__(self, **kwargs):
        # Set defaults from class attributes, then override with kwargs
        for name in self._field_annotations:
            if name in ('model_config', 'model_fields'):
                continue
            if name in kwargs:
                value = kwargs[name]
            elif hasattr(self.__class__, name):
                value = getattr(self.__class__, name)
                # If it's a Field default_factory result, it's already resolved
            else:
                value = None
            setattr(self, name, value)

        # Run validators
        for attr_name in dir(self.__class__):
            attr = getattr(self.__class__, attr_name, None)
            if callable(attr) and hasattr(attr, '__func__'):
                # Check if it's a field_validator-decorated method
                pass

    def model_copy(self, /, update=None):
        """Create a copy with optional updates."""
        data = {}
        for name in self._field_annotations:
            if name in ('model_config', 'model_fields'):
                continue
            data[name] = getattr(self, name, None)
        if update:
            data.update({k: v for k, v in update.items() if v is not None})
        return self.__class__(**data)

    def model_dump(self):
        """Dump model to dict."""
        result = {}
        for name in self._field_annotations:
            if name in ('model_config', 'model_fields'):
                continue
            result[name] = getattr(self, name, None)
        return result

    def __iter__(self):
        """Iterate over field name-value pairs (needed for dict(model))."""
        for name in self._field_annotations:
            if name in ('model_config', 'model_fields'):
                continue
            yield name, getattr(self, name, None)
