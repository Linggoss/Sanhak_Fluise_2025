from setuptools import setup, find_packages

setup(
    name="gpt_agent_sdk",
    version="0.1.0",
    description="GPT 기반 대화 요약 및 ToolCalling 에이전트 SDK",
    author="jihigan",
    packages=find_packages(),
    install_requires=[
        "openai>=1.0.0"
    ],
    python_requires=">=3.8",
)

