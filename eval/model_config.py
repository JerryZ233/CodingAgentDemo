"""Only model settings may cross from a host configuration into the Linux evaluation environment."""
FIELDS = {
    "api_key": "LLM_API_KEY", "api_url": "LLM_API_URL", "model": "LLM_MODEL",
    "provider": "LLM_PROVIDER", "max_tokens": "LLM_MAX_TOKENS",
    "temperature": "LLM_TEMPERATURE", "max_retries": "LLM_MAX_RETRIES",
}


def apply_model_config(defaults, local, environment):
    for data in (local, defaults):
        if not isinstance(data, dict) or not isinstance(data.get("llm", {}), dict):
            raise ValueError("Model configuration must contain an llm mapping")
        for field, key in FIELDS.items():
            value = data.get("llm", {}).get(field)
            if value is not None:
                if not isinstance(value, (str, int, float)) or isinstance(value, bool):
                    raise ValueError("Invalid model configuration field: " + field)
                environment.setdefault(key, str(value))
