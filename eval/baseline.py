from harbor.agents.installed.base import BaseInstalledAgent


class NoopAgent(BaseInstalledAgent):
    @staticmethod
    def name():
        return "initial-code"

    def version(self):
        return "1"

    async def install(self, environment):
        pass

    async def run(self, instruction, environment, context):
        pass
