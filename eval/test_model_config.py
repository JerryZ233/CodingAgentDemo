import unittest
from model_config import apply_model_config


class ModelConfigTest(unittest.TestCase):
    def test_environment_then_local_then_defaults_and_only_model_fields(self):
        environment = {"LLM_MODEL": "explicit"}
        apply_model_config({"llm": {"model": "default", "provider": "generic", "max_tokens": 4096}},
                           {"llm": {"api_key": "example-secret", "model": "local", "temperature": 0.2},
                            "agent": {"workspace_dir": "C:/host"}, "tools": {"enabled": []}}, environment)
        self.assertEqual("explicit", environment["LLM_MODEL"])
        self.assertEqual("example-secret", environment["LLM_API_KEY"])
        self.assertEqual("generic", environment["LLM_PROVIDER"])
        self.assertEqual("4096", environment["LLM_MAX_TOKENS"])
        self.assertEqual("0.2", environment["LLM_TEMPERATURE"])
        self.assertNotIn("WORKSPACE_DIR", environment)

    def test_invalid_configuration_does_not_echo_secret_values(self):
        with self.assertRaises(ValueError) as caught:
            apply_model_config({}, {"llm": {"api_key": {"secret": "private"}}}, {})
        self.assertNotIn("private", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
