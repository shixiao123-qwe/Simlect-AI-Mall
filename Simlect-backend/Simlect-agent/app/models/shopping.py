"""Structured user requirements only. Product facts always come from Java."""
from decimal import Decimal
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator

BRAND_ALIASES = {"apple":"苹果", "huawei":"华为", "xiaomi":"小米", "sony":"索尼"}
ATTRIBUTE_ALIASES = {"色彩":"颜色", "ram":"运行内存", "内存容量":"运行内存", "rom":"存储容量",
                     "机身存储":"存储容量", "brand":"品牌", "新旧":"成色", "降噪功能":"降噪"}
def canonical(value):
    value = str(value).strip().lower().replace(" ", "")
    return BRAND_ALIASES.get(value, value)

def attribute_name(value):
    key = canonical(value)
    return ATTRIBUTE_ALIASES.get(key, key)

class HardAttribute(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    name: str = Field(min_length=1, max_length=30)
    value: str = Field(min_length=1, max_length=100)
    operator: Literal["eq", "ne", "gte", "lte"] = "eq"

class ShoppingRequirement(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    category: str | None = Field(None, min_length=1, max_length=80)
    min_price: Decimal | None = Field(None, ge=0, max_digits=10, decimal_places=2)
    max_price: Decimal | None = Field(None, ge=0, max_digits=10, decimal_places=2)
    scenario: str | None = Field(None, min_length=1, max_length=80)
    preferences: list[str] = Field(default_factory=list, max_length=10)
    preferred_brands: list[str] = Field(default_factory=list, max_length=10)
    excluded_brands: list[str] = Field(default_factory=list, max_length=10)
    hard_attributes: list[HardAttribute] = Field(default_factory=list, max_length=10)

    @model_validator(mode="after")
    def valid_range(self):
        if self.min_price is not None and self.max_price is not None and self.min_price > self.max_price:
            raise ValueError("预算下限高于上限，请明确预算区间")
        for values in (self.preferences, self.preferred_brands, self.excluded_brands):
            if any(not p.strip() or len(p)>80 for p in (values or [])):
                raise ValueError("invalid preference")
        if {canonical(b) for b in self.preferred_brands or []} & {canonical(b) for b in self.excluded_brands or []}:
            raise ValueError("品牌同时被偏好和排除，请明确选择")
        return self

class ShoppingPatch(ShoppingRequirement):
    preferences: list[str] | None = Field(None, max_length=10)
    preferred_brands: list[str] | None = Field(None, max_length=10)
    excluded_brands: list[str] | None = Field(None, max_length=10)
    hard_attributes: list[HardAttribute] | None = Field(None, max_length=10)
    reset: bool = False
    clear: list[Literal["category", "min_price", "max_price", "scenario", "preferences", "preferred_brands", "excluded_brands", "hard_attributes"]] = Field(default_factory=list)
    clarification: str | None = Field(None, max_length=200)

def merge_requirement(previous: dict, patch: ShoppingPatch) -> ShoppingRequirement:
    data = {} if patch.reset else ShoppingRequirement.model_validate(previous).model_dump()
    defaults = ShoppingRequirement().model_dump()
    for field in patch.clear:
        data[field] = defaults[field]
    for field in patch.model_fields_set - {"reset", "clear", "clarification"}:
        value = getattr(patch, field)
        if value is None:
            continue
        if field == "hard_attributes":
            updated = {attribute_name(a.name) for a in value}
            value = [a for a in data.get(field, []) if attribute_name(a["name"] if isinstance(a,dict) else a.name) not in updated] + list(value)
        elif field in ("preferences", "excluded_brands"):
            value = list(dict.fromkeys(data.get(field, []) + value))
        data[field] = value
    return ShoppingRequirement.model_validate(data)
