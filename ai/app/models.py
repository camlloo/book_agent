from typing import Literal
from pydantic import BaseModel, Field, model_validator


class WriteRequest(BaseModel):
    topic: str = Field(min_length=1, max_length=128)
    stage: Literal["OUTLINE", "DRAFT", "POLISH"]
    instructions: str = Field(default="", max_length=2000)
    content: str = Field(default="", max_length=200000)
    book_id: int | None = Field(default=None, gt=0)
    book_revision: int | None = Field(default=None, gt=0)

    @model_validator(mode="after")
    def validate_book_scope(self):
        if (self.book_id is None) != (self.book_revision is None):
            raise ValueError("book_id and book_revision must be supplied together")
        return self


class Article(BaseModel):
    id: int
    title: str
    content: str
    updatedAt: str | None = None


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1,max_length=4000)


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    articles: list[Article]
    history: list[ChatMessage] = Field(default_factory=list,max_length=6)


class BookFile(BaseModel):
    id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=255)
    extension: Literal["pdf", "docx", "txt", "md"]
    size: int = Field(gt=0, le=20 * 1024 * 1024)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    url: str


class BookIndexRequest(BaseModel):
    chunking: Literal["sentence", "semantic"] = "sentence"
    book_id: int = Field(gt=0)
    revision: int = Field(gt=0)
    title: str = Field(min_length=1, max_length=128)
    content: str = Field(max_length=1000000)
    files: list[BookFile] = Field(default_factory=list, max_length=10)


class BookDeleteRequest(BaseModel):
    book_id: int = Field(gt=0)
    revision: int = Field(gt=0)


class BookAskRequest(BookDeleteRequest):
    question: str = Field(min_length=1, max_length=500)
    top_k: int = Field(default=5, ge=1, le=10)
    history: list[ChatMessage] = Field(default_factory=list,max_length=6)
